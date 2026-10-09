package fi.rosu.afkroulette;

import com.google.inject.Provides;
import fi.rosu.afkroulette.reel.RollOverlay;
import fi.rosu.afkroulette.sync.SyncManager;
import fi.rosu.afkroulette.tracker.TaskTracker;
import fi.rosu.afkroulette.ui.AfkRoulettePanel;
import java.awt.image.BufferedImage;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "AFK Roulette",
	description = "Task roulette plus a group tracker: shared stats, gear and item search",
	tags = {"task", "tasker", "roulette", "afk", "group", "ironman", "gim", "items", "search"}
)
public class AfkRoulettePlugin extends Plugin
{
	private static final Set<String> USER_SETTINGS = Set.of("serverEnabled", "shareData", "groupToken");
	private static final int INVENTORY_SLOTS = 28;
	private static final int EQUIPMENT_SLOTS = 14;
	/** Containers whose slot order matters (shown as grids); others are sent compacted. */
	private static final int COMPACT = -1;

	@Inject
	private Client client;
	@Inject
	private AfkRouletteConfig config;
	@Inject
	private GroupLeaver leaver;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private ItemManager itemManager;
	@Inject
	private SyncManager sync;
	@Inject
	private PlayerState player;
	@Inject
	private TaskTracker tracker;
	@Inject
	private EventBus eventBus;
	@Inject
	private ClientThread clientThread;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private RollOverlay rollOverlay;
	@Inject
	private ConfigManager configManager;

	private AfkRoulettePanel panel;
	private NavigationButton navButton;
	/** Game ticks until a full quest/skill snapshot is taken after login. */
	private int snapshotInTicks = -1;
	/** The RuneLite config profile in use: switching profiles is not the user changing settings. */
	private long configProfileId;

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
		overlayManager.add(rollOverlay);
		configProfileId = currentConfigProfileId();
		eventBus.register(tracker);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			snapshotInTicks = 2;
		}
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(tracker);
		clientThread.invoke(tracker::reset);
		overlayManager.remove(rollOverlay);
		rollOverlay.stop();
		panel.onShutdown();
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
			tracker.reset();
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
				tracker.refresh();
				if (panel != null)
				{
					panel.onPlayerChanged();
				}
			}
		}
		if (snapshotInTicks > 0 && --snapshotInTicks == 0)
		{
			tracker.baseline();
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
		if (!sharing())
		{
			return;
		}
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
		// Only the user's settings: the tracker's own progress saves (RS-profile keys
		// in the same group) must not reload everything.
		if (AfkRouletteConfig.GROUP.equals(event.getGroup()) && panel != null
			&& USER_SETTINGS.contains(event.getKey()))
		{
			removeSharedDataIfStopped(event);
			sync.resendAll();
			tracker.refresh();
			panel.onConfigChanged();
			// Nothing is collected while sharing is off, so take a fresh snapshot when it turns on.
			clientThread.invokeLater(() ->
			{
				collectSkills();
				collectQuests();
			});
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		// May be posted from any thread; the tracker lives on the client thread.
		String previous = event.getPreviousProfile();
		clientThread.invoke(() ->
		{
			// Logging in (no previous profile) is handled by the login snapshot.
			if (previous == null)
			{
				return;
			}
			// Saved task progress is per RS profile: keep the old one's, start over and re-baseline.
			tracker.resetForNewProfile(previous);
			snapshotInTicks = 2;
			tracker.refresh();
		});
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		configProfileId = currentConfigProfileId();
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		tracker.saveNow();
	}

	private long currentConfigProfileId()
	{
		return configManager.getProfile() == null ? 0 : configManager.getProfile().getId();
	}

	/**
	 * Sharing turned off, or the group token replaced: the snapshot already uploaded under the
	 * old token is removed from that group (fire and forget). Tasks and streaks there stay.
	 */
	private void removeSharedDataIfStopped(ConfigChanged event)
	{
		// A RuneLite profile switch also reports every differing setting as changed.
		if (currentConfigProfileId() != configProfileId)
		{
			return;
		}
		if ("shareData".equals(event.getKey()))
		{
			if (Boolean.parseBoolean(event.getOldValue()) && !Boolean.parseBoolean(event.getNewValue()))
			{
				leaver.leave(config.groupToken(), true);
			}
		}
		else if ("groupToken".equals(event.getKey()))
		{
			String oldToken = event.getOldValue() == null ? "" : event.getOldValue().trim();
			String newToken = event.getNewValue() == null ? "" : event.getNewValue().trim();
			if (!oldToken.isEmpty() && !oldToken.equals(newToken))
			{
				leaver.leave(oldToken, true);
			}
		}
	}

	/** Quest states rarely change; also acts as a once-a-minute "online" heartbeat. */
	@Schedule(period = 60, unit = ChronoUnit.SECONDS)
	public void refreshQuests()
	{
		collectQuests();
		// Picks up tasks rolled, skipped or finished on Discord or the website.
		if (trackedName() != null)
		{
			tracker.refresh();
		}
	}

	@Schedule(period = 2, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void upload()
	{
		sync.submit(player.getName());
	}

	/** Whether anything may be uploaded: the server is on and the player chose to share. */
	private boolean sharing()
	{
		return config.serverEnabled() && config.shareData();
	}

	private void collectSkills()
	{
		if (!sharing())
		{
			return;
		}
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
		// The tracker reads the quest log whenever the server is on; only uploading needs sharing.
		if (!config.serverEnabled())
		{
			return;
		}
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
		tracker.onQuestStates(quests);
		if (!config.shareData())
		{
			return;
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
		// Leagues, Deadman etc. are separate accounts in practice; never mix them with the main game.
		if (RuneScapeProfileType.getCurrent(client) != RuneScapeProfileType.STANDARD)
		{
			return null;
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
