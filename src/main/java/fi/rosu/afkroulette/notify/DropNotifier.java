package fi.rosu.afkroulette.notify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.rosu.afkroulette.AfkRouletteConfig;
import fi.rosu.afkroulette.ApiClient;
import fi.rosu.afkroulette.PlayerState;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Posts the player's rare drops, new collection log items and pets to the group's
 * Discord channel, through the AFK Roulette server (which holds the channel webhook).
 * Drops pass on value, on rarity (from the wiki drop tables, fetched per source from the
 * server) or as an event's collection log unique; one loot event is one post.
 */
@Slf4j
@Singleton
public class DropNotifier
{
	private static final String CLOG_PREFIX = "New item added to your collection log: ";
	private static final String PET_FOLLOW = "You have a funny feeling like you";
	private static final String PET_BACKPACK = "You feel something weird sneaking into your backpack.";
	/** A pet that is also a new log slot: wait this long for the log message to name it. */
	private static final int PET_NAME_WAIT_TICKS = 5;
	private static final int SCREENSHOT_MAX_WIDTH = 1600;
	private static final int SCREENSHOT_MAX_BYTES = 2_500_000;
	private static final int MAX_CACHED_SOURCES = 300;

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final AfkRouletteConfig config;
	private final ApiClient api;
	private final PlayerState player;
	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;
	private final ChatMessageManager chatMessageManager;
	/** Rarity and uniques per loot source (lower case), from the server. */
	private final Map<String, SourceInfo> sources = new ConcurrentHashMap<>();
	/** "No channel set" is said once per session, not on every drop. */
	private volatile boolean toldNoChannel;
	/** Client thread: ticks left to name a pet from the collection log message, 0 = none pending. */
	private int petWaitTicks;

	private static final class SourceInfo
	{
		final Map<String, Double> rarity;
		final Set<String> uniques;

		SourceInfo(Map<String, Double> rarity, Set<String> uniques)
		{
			this.rarity = rarity;
			this.uniques = uniques;
		}
	}

	private static final class Drop
	{
		final int id;
		final String name;
		final int quantity;
		final long value;

		Drop(int id, String name, int quantity, long value)
		{
			this.id = id;
			this.name = name;
			this.quantity = quantity;
			this.value = value;
		}
	}

	@Inject
	DropNotifier(Client client, ClientThread clientThread, ItemManager itemManager, AfkRouletteConfig config,
		ApiClient api, PlayerState player, DrawManager drawManager, ScheduledExecutorService executor,
		ChatMessageManager chatMessageManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.config = config;
		this.api = api;
		this.player = player;
		this.drawManager = drawManager;
		this.executor = executor;
		this.chatMessageManager = chatMessageManager;
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		NPC npc = event.getNpc();
		handleLoot(npc == null ? null : npc.getName(), event.getItems(), false);
	}

	/** Chests, clues, raids, pickpockets... (NPC kills come through NpcLootReceived). Needs the Loot Tracker plugin. */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (event.getType() == LootRecordType.NPC)
		{
			return;
		}
		// Never another player's name (PvP loot).
		String source = event.getType() == LootRecordType.PLAYER ? null : event.getName();
		handleLoot(source, event.getItems(), true);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE || !enabled())
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());
		if (message.startsWith(CLOG_PREFIX))
		{
			String item = message.substring(CLOG_PREFIX.length()).trim();
			if (item.isEmpty())
			{
				return;
			}
			if (petWaitTicks > 0)
			{
				// The pet's own log slot: one post, with its name.
				petWaitTicks = 0;
				send("pet", item, null, null);
			}
			else if (config.notifyCollectionLog())
			{
				send("clog", item, null, null);
			}
		}
		else if (config.notifyPets() && (message.startsWith(PET_FOLLOW) || message.equals(PET_BACKPACK)))
		{
			petWaitTicks = PET_NAME_WAIT_TICKS;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (petWaitTicks > 0 && --petWaitTicks == 0 && enabled())
		{
			send("pet", "", null, null);
		}
	}

	/** Client thread. */
	private void handleLoot(String source, Collection<ItemStack> items, boolean event)
	{
		if (items == null || items.isEmpty() || !enabled())
		{
			return;
		}
		// One kill can drop the same item in several stacks: count them together.
		Map<Integer, Integer> quantities = new LinkedHashMap<>();
		for (ItemStack stack : items)
		{
			quantities.merge(itemManager.canonicalize(stack.getId()), stack.getQuantity(), Integer::sum);
		}
		List<Drop> drops = new ArrayList<>();
		for (Map.Entry<Integer, Integer> e : quantities.entrySet())
		{
			ItemComposition item = itemManager.getItemComposition(e.getKey());
			long price = itemManager.getItemPrice(e.getKey());
			if (price <= 0)
			{
				price = item.getHaPrice();
			}
			drops.add(new Drop(e.getKey(), item.getName(), e.getValue(), Math.max(0, price) * e.getValue()));
		}

		boolean needInfo = source != null && (config.notifyRarity() > 0 || (event && config.notifyUniques()));
		if (!needInfo)
		{
			decide(drops, null, source, event);
			return;
		}
		String key = source.toLowerCase(Locale.ROOT);
		SourceInfo cached = sources.get(key);
		if (cached != null)
		{
			decide(drops, cached, source, event);
			return;
		}
		Map<String, String> q = new HashMap<>();
		q.put("source", source);
		api.get("/api/drops/info", q, (json, error) ->
		{
			SourceInfo info = error == null ? parseInfo(json) : null;
			if (info != null)
			{
				if (sources.size() >= MAX_CACHED_SOURCES)
				{
					sources.clear();
				}
				sources.put(key, info);
			}
			// Without the info, value and the item lists still work.
			clientThread.invokeLater(() -> decide(drops, info, source, event));
		});
	}

	/** Client thread: picks the drops to post and sends them as one notification. */
	private void decide(List<Drop> drops, SourceInfo info, String source, boolean event)
	{
		Set<String> always = names(config.notifyAlways());
		Set<String> never = names(config.notifyNever());
		long minValue = Math.max(0, config.notifyValue());
		int minRarity = Math.max(0, config.notifyRarity());
		List<Map<String, Object>> posted = new ArrayList<>();
		for (Drop d : drops)
		{
			String key = d.name.toLowerCase(Locale.ROOT);
			if (never.contains(key))
			{
				continue;
			}
			Double rarity = info == null ? null : info.rarity.get(key);
			boolean valueOk = minValue > 0 && d.value >= minValue;
			boolean rarityOk = minRarity > 0 && rarity != null && rarity >= minRarity;
			boolean wanted;
			if (minValue > 0 && minRarity > 0 && config.notifyRequireBoth())
			{
				wanted = valueOk && rarityOk;
			}
			else
			{
				wanted = valueOk || rarityOk;
			}
			boolean unique = event && config.notifyUniques() && info != null && info.uniques.contains(key);
			if (wanted || unique || always.contains(key))
			{
				Map<String, Object> item = new HashMap<>();
				item.put("name", d.name);
				item.put("itemId", d.id);
				item.put("quantity", d.quantity);
				item.put("value", d.value);
				if (rarity != null)
				{
					item.put("rarity", rarity);
				}
				posted.add(item);
			}
		}
		if (!posted.isEmpty() && enabled())
		{
			send("loot", null, posted, source);
		}
	}

	private boolean enabled()
	{
		return config.serverEnabled() && config.notifyDiscord() && player.getName() != null
			&& !config.groupToken().trim().isEmpty()
			&& RuneScapeProfileType.getCurrent(client) == RuneScapeProfileType.STANDARD;
	}

	/** Client thread. The screenshot is taken on the next frame and sent from a worker thread. */
	private void send(String type, String item, List<Map<String, Object>> items, String source)
	{
		Map<String, Object> body = new HashMap<>();
		body.put("name", player.getName());
		body.put("type", type);
		if (item != null)
		{
			body.put("item", item);
		}
		if (items != null)
		{
			body.put("items", items);
		}
		if (source != null)
		{
			body.put("source", source);
		}
		if (!config.notifyScreenshot())
		{
			post(body);
			return;
		}
		drawManager.requestNextFrameListener(image -> executor.execute(() ->
		{
			String screenshot = encode(image);
			if (screenshot != null)
			{
				body.put("screenshot", screenshot);
			}
			post(body);
		}));
	}

	private void post(Map<String, Object> body)
	{
		// The settings may have changed (or the plugin stopped) while the screenshot was taken.
		if (!config.serverEnabled() || !config.notifyDiscord())
		{
			return;
		}
		api.post("/api/plugin/notify", body, (json, error) ->
		{
			if (error == null)
			{
				return;
			}
			log.debug("Discord notification failed: {}", error);
			if (error.contains("No Discord channel") && !toldNoChannel)
			{
				toldNoChannel = true;
				chatMessageManager.queue(QueuedMessage.builder()
					.type(ChatMessageType.GAMEMESSAGE)
					.runeLiteFormattedMessage(new ChatMessageBuilder()
						.append(ChatColorType.HIGHLIGHT)
						.append("AFK Roulette: ")
						.append(ChatColorType.NORMAL)
						.append("set your group's Discord channel in the Group tab to post drops.")
						.build())
					.build());
			}
		});
	}

	private static SourceInfo parseInfo(JsonObject json)
	{
		if (json == null)
		{
			return null;
		}
		Map<String, Double> rarity = new HashMap<>();
		Set<String> uniques = new HashSet<>();
		try
		{
			if (json.has("rarity") && json.get("rarity").isJsonObject())
			{
				for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("rarity").entrySet())
				{
					if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber())
					{
						rarity.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsDouble());
					}
				}
			}
			if (json.has("uniques") && json.get("uniques").isJsonArray())
			{
				for (JsonElement e : json.getAsJsonArray("uniques"))
				{
					if (e.isJsonPrimitive())
					{
						uniques.add(e.getAsString().toLowerCase(Locale.ROOT));
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Bad drop info", e);
			return null;
		}
		return new SourceInfo(rarity, uniques);
	}

	/** JPEG as base64, or null if it can't be made small enough. */
	private static String encode(Image image)
	{
		try
		{
			BufferedImage src = ImageUtil.bufferedImageFromImage(image);
			int width = src.getWidth();
			int height = src.getHeight();
			if (width > SCREENSHOT_MAX_WIDTH)
			{
				height = height * SCREENSHOT_MAX_WIDTH / width;
				width = SCREENSHOT_MAX_WIDTH;
			}
			// JPEG has no alpha channel: draw onto a plain RGB image.
			BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = rgb.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(src, 0, 0, width, height, null);
			g.dispose();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (!ImageIO.write(rgb, "jpg", out) || out.size() > SCREENSHOT_MAX_BYTES)
			{
				return null;
			}
			return Base64.getEncoder().encodeToString(out.toByteArray());
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Screenshot failed", e);
			return null;
		}
	}

	private static Set<String> names(String list)
	{
		if (list == null || list.trim().isEmpty())
		{
			return Collections.emptySet();
		}
		Set<String> out = new HashSet<>();
		for (String part : list.split("[,\\n]"))
		{
			String s = part.trim().toLowerCase(Locale.ROOT);
			if (!s.isEmpty())
			{
				out.add(s);
			}
		}
		return out;
	}
}
