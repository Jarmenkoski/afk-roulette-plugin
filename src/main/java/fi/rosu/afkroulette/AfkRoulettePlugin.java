package fi.rosu.afkroulette;

import com.google.inject.Provides;
import fi.rosu.afkroulette.sync.SyncManager;
import fi.rosu.afkroulette.ui.AfkRoulettePanel;
import java.awt.image.BufferedImage;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "AFK Roulette",
	description = "Task roulette plus a group tracker: shared stats, gear and item search",
	tags = {"task", "tasker", "roulette", "afk", "group", "ironman", "gim", "items", "search"}
)
public class AfkRoulettePlugin extends Plugin
{
	private static final int INVENTORY_SLOTS = 28;
	private static final int EQUIPMENT_SLOTS = 14;
	/** Containers whose slot order matters (shown as grids); others are sent compacted. */
	private static final int COMPACT = -1;

	/** Leagues, Deadman etc. are separate accounts in practice; never mix them with the main game. */
	private static final EnumSet<WorldType> IGNORED_WORLDS = EnumSet.of(
		WorldType.SEASONAL, WorldType.DEADMAN, WorldType.TOURNAMENT_WORLD,
		WorldType.PVP_ARENA, WorldType.BETA_WORLD, WorldType.QUEST_SPEEDRUNNING);

	@Inject
	private Client client;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private ItemManager itemManager;
	@Inject
	private SyncManager sync;
	@Inject
	private PlayerState player;

	private AfkRoulettePanel panel;
	private NavigationButton navButton;
	/** Game ticks until a full quest/skill snapshot is taken after login. */
	private int snapshotInTicks = -1;

	@Override
	protected void startUp()
	{
		panel = injector.getInstance(AfkRoulettePanel.class);
		BufferedImage icon = ImageUtil.loadImageResource(AfkRoulettePlugin.class, "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("AFK Roulette")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			snapshotInTicks = 2;
		}
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
		sync.reset();
		player.setName(null);
		snapshotInTicks = -1;
	}

	@Provides
	AfkRouletteConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AfkRouletteConfig.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			snapshotInTicks = 3;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			player.setName(null);
			if (panel != null)
			{
				panel.onPlayerChanged();
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Player local = client.getLocalPlayer();
		if (local != null && local.getName() != null)
		{
			String name = PlayerState.normalize(local.getName());
			if (!name.equals(player.getName()))
			{
				player.setName(name);
				if (panel != null)
				{
					panel.onPlayerChanged();
				}
			}
		}
		if (snapshotInTicks > 0 && --snapshotInTicks == 0)
		{
			collectSkills();
			collectQuests();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		collectSkills();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		String name = trackedName();
		if (name == null)
		{
			return;
		}
		ItemContainer container = event.getItemContainer();
		int id = event.getContainerId();
		if (id == InventoryID.INV)
		{
			sync.update(name, "inventory", flatItems(container, INVENTORY_SLOTS));
		}
		else if (id == InventoryID.WORN)
		{
			sync.update(name, "equipment", flatItems(container, EQUIPMENT_SLOTS));
		}
		else if (id == InventoryID.BANK)
		{
			sync.update(name, "bank", flatItems(container, COMPACT));
		}
		else if (id == InventoryID.SEED_VAULT)
		{
			sync.update(name, "seed_vault", flatItems(container, COMPACT));
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (AfkRouletteConfig.GROUP.equals(event.getGroup()) && panel != null)
		{
			sync.resendAll();
			panel.onConfigChanged();
		}
	}

	/** Quest states rarely change; also acts as a once-a-minute "online" heartbeat. */
	@Schedule(period = 60, unit = ChronoUnit.SECONDS)
	public void refreshQuests()
	{
		collectQuests();
	}

	@Schedule(period = 2, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void upload()
	{
		sync.submit(player.getName());
	}

	private void collectSkills()
	{
		String name = trackedName();
		if (name == null)
		{
			return;
		}
		Map<String, Integer> levels = new HashMap<>();
		Map<String, Integer> xp = new HashMap<>();
		for (Skill skill : Skill.values())
		{
			levels.put(skill.getName(), client.getRealSkillLevel(skill));
			xp.put(skill.getName(), client.getSkillExperience(skill));
		}
		sync.update(name, "levels", levels);
		sync.update(name, "xp", xp);
	}

	private void collectQuests()
	{
		String name = trackedName();
		if (name == null)
		{
			return;
		}
		Map<String, String> quests = new HashMap<>();
		for (Quest quest : Quest.values())
		{
			quests.put(quest.getName(), quest.getState(client).name());
		}
		sync.update(name, "quests", quests);
		sync.update(name, "world", client.getWorld());
		sync.update(name, "heartbeat", System.currentTimeMillis() / 60_000);

		// Container events only fire on change; read them directly too, so a plugin
		// started mid-session (or events that came before login finished) still sync.
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory != null)
		{
			sync.update(name, "inventory", flatItems(inventory, INVENTORY_SLOTS));
		}
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn != null)
		{
			sync.update(name, "equipment", flatItems(worn, EQUIPMENT_SLOTS));
		}
	}

	/** The player whose data may be collected right now, or null. */
	private String trackedName()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return null;
		}
		for (WorldType type : client.getWorldType())
		{
			if (IGNORED_WORLDS.contains(type))
			{
				return null;
			}
		}
		return PlayerState.normalize(client.getLocalPlayer().getName());
	}

	/** [id, qty, id, qty, ...] with noted items canonicalized so searches count them too. */
	private List<Integer> flatItems(ItemContainer container, int slots)
	{
		List<Integer> out = new ArrayList<>();
		if (slots == COMPACT)
		{
			for (Item item : container.getItems())
			{
				if (isRealItem(item))
				{
					out.add(itemManager.canonicalize(item.getId()));
					out.add(item.getQuantity());
				}
			}
			return out;
		}
		for (int i = 0; i < slots; i++)
		{
			Item item = container.getItem(i);
			if (isRealItem(item))
			{
				out.add(itemManager.canonicalize(item.getId()));
				out.add(item.getQuantity());
			}
			else
			{
				out.add(0);
				out.add(0);
			}
		}
		return out;
	}

	private boolean isRealItem(Item item)
	{
		return item != null && item.getId() > 0 && item.getQuantity() > 0
			&& itemManager.getItemComposition(item.getId()).getPlaceholderTemplateId() == -1;
	}
}
