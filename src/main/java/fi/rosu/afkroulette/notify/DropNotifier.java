package fi.rosu.afkroulette.notify;

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
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.events.ChatMessage;
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
 */
@Slf4j
@Singleton
public class DropNotifier
{
	private static final String CLOG_PREFIX = "New item added to your collection log: ";
	private static final String PET_FOLLOW = "You have a funny feeling like you";
	private static final String PET_BACKPACK = "You feel something weird sneaking into your backpack.";
	/** Screenshots wider than this are scaled down; larger files are sent without one. */
	private static final int SCREENSHOT_MAX_WIDTH = 1600;
	private static final int SCREENSHOT_MAX_BYTES = 2_500_000;

	private final Client client;
	private final ItemManager itemManager;
	private final AfkRouletteConfig config;
	private final ApiClient api;
	private final PlayerState player;
	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;
	private final ChatMessageManager chatMessageManager;
	/** "No channel set" is said once per session, not on every drop. */
	private volatile boolean toldNoChannel;

	@Inject
	DropNotifier(Client client, ItemManager itemManager, AfkRouletteConfig config, ApiClient api, PlayerState player,
		DrawManager drawManager, ScheduledExecutorService executor, ChatMessageManager chatMessageManager)
	{
		this.client = client;
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
		handleLoot(npc == null ? null : npc.getName(), event.getItems());
	}

	/** Chests, clues, raids, pickpockets... (NPC kills come through NpcLootReceived). */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (event.getType() != LootRecordType.NPC)
		{
			handleLoot(event.getName(), event.getItems());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE || !enabled())
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());
		if (config.notifyCollectionLog() && message.startsWith(CLOG_PREFIX))
		{
			String item = message.substring(CLOG_PREFIX.length()).trim();
			if (!item.isEmpty())
			{
				send("clog", item, 0, 1, 0, null);
			}
		}
		else if (config.notifyPets() && (message.startsWith(PET_FOLLOW) || message.equals(PET_BACKPACK)))
		{
			send("pet", "", 0, 1, 0, null);
		}
	}

	private void handleLoot(String source, Collection<ItemStack> items)
	{
		if (items == null || !enabled())
		{
			return;
		}
		Set<String> always = names(config.notifyAlways());
		Set<String> never = names(config.notifyNever());
		long threshold = Math.max(0, config.notifyValue());
		// One kill can drop the same item in several stacks: count them together.
		Map<Integer, Integer> quantities = new HashMap<>();
		for (ItemStack stack : items)
		{
			quantities.merge(itemManager.canonicalize(stack.getId()), stack.getQuantity(), Integer::sum);
		}
		for (Map.Entry<Integer, Integer> e : quantities.entrySet())
		{
			int id = e.getKey();
			int quantity = e.getValue();
			ItemComposition item = itemManager.getItemComposition(id);
			String name = item.getName();
			String key = name.toLowerCase(Locale.ROOT);
			if (never.contains(key))
			{
				continue;
			}
			long price = itemManager.getItemPrice(id);
			if (price <= 0)
			{
				price = item.getHaPrice();
			}
			long value = Math.max(0, price) * quantity;
			if (always.contains(key) || (threshold > 0 && value >= threshold))
			{
				send("loot", name, id, quantity, value, source);
			}
		}
	}

	private boolean enabled()
	{
		return config.serverEnabled() && config.notifyDiscord() && player.getName() != null
			&& !config.groupToken().trim().isEmpty()
			&& RuneScapeProfileType.getCurrent(client) == RuneScapeProfileType.STANDARD;
	}

	/** Client thread. The screenshot is taken on the next frame and sent from a worker thread. */
	private void send(String type, String item, int itemId, int quantity, long value, String source)
	{
		Map<String, Object> body = new HashMap<>();
		body.put("name", player.getName());
		body.put("type", type);
		body.put("item", item);
		if (itemId > 0)
		{
			body.put("itemId", itemId);
		}
		body.put("quantity", quantity);
		body.put("value", value);
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
			return new HashSet<>();
		}
		List<String> parts = new ArrayList<>(Arrays.asList(list.split("[,\\n]")));
		return parts.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
			.collect(Collectors.toSet());
	}
}
