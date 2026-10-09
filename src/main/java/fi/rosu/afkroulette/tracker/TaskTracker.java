package fi.rosu.afkroulette.tracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import fi.rosu.afkroulette.AfkRouletteConfig;
import fi.rosu.afkroulette.ApiClient;
import fi.rosu.afkroulette.PlayerState;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Completes the player's active tasks automatically from game events.
 *
 * The server attaches a "verify" spec to every rolled task (see the backend's
 * verify.py). This class follows the active task of each category, counts
 * progress from xp drops, inventory gains, kill-count / collection log / diary
 * chat messages, kills and the quest log, and marks the task done on the
 * server when the target is reached. Tasks without a spec stay manual.
 *
 * All state is touched on the client thread only; server replies hop back to it
 * with clientThread.invokeLater().
 */
@Slf4j
@Singleton
public class TaskTracker
{
	public static final String[] CATEGORIES = {"afk", "task", "boss", "collection"};

	/** "Your Scurrius kill count is: 5.", "Your Varrock Rooftop lap count is: 12." ... */
	private static final Pattern KC = Pattern.compile("^Your (.+?) count is: ?([\\d,]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern RIFTS = Pattern.compile("^Amount of rifts you have closed: ?[\\d,]+", Pattern.CASE_INSENSITIVE);
	private static final Pattern SLAYER = Pattern.compile("^You've completed [\\d,]+ tasks", Pattern.CASE_INSENSITIVE);
	private static final Pattern CLOG = Pattern.compile("^New item added to your collection log: (.+?)\\.?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern DIARY = Pattern.compile(
		"^Congratulations!.*completed all of the (easy|medium|hard|elite) tasks in the (.+?) area", Pattern.CASE_INSENSITIVE);

	/** Save progress at most this often (game ticks, ~1 minute); also saved on logout and completion. */
	private static final int SAVE_EVERY_TICKS = 100;
	private static final String PROGRESS_KEY = "progress.";
	/** Wait this long (game ticks, ~30 s) before retrying a failed completion. */
	private static final int RETRY_TICKS = 50;

	/** Change notification for the panel. Called on the client thread. */
	public interface Listener
	{
		void onTrackerChanged(String category, boolean completed);
	}

	private static final class Tracked
	{
		String category;
		String name;
		String type;
		String skill;
		Set<String> skills = new HashSet<>();
		Set<String> names = new HashSet<>();
		String quest;
		String region;
		String tier;
		int target;
		volatile int progress;
		Set<String> seen = new HashSet<>();
		boolean completing;
		/** Identity of this roll: the same task name can be rolled again later. */
		String rollId;
		/** Level tasks: level the player had when tracking started, and levels to gain. */
		int levelBase;
		int levelGain;
		/** Quest tasks: whether the quest log has been checked since tracking started. */
		boolean questChecked;
		/** "done", or "already" for a quest that was finished before it was rolled. */
		String completeAs = "done";
		/** Game tick before which a failed completion is not retried. */
		int retryAtTick;
	}

	private static final class PendingGain
	{
		final String item;
		final int qty;
		final int tick;

		PendingGain(String item, int qty, int tick)
		{
			this.item = item;
			this.qty = qty;
			this.tick = tick;
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final ApiClient api;
	private final PlayerState player;
	private final ConfigManager configManager;
	private final ChatMessageManager chatMessageManager;
	private final AfkRouletteConfig config;
	private final Gson gson;

	private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private final Map<String, Integer> lastXpTick = new HashMap<>();
	private final List<PendingGain> pendingGains = new ArrayList<>();
	private Map<String, Integer> lastInventory;
	private boolean dirty;
	private int ticksSinceSave;
	/** Bumped by reset(); server replies from an older generation are dropped. */
	private volatile int generation;

	@Inject
	TaskTracker(Client client, ClientThread clientThread, ItemManager itemManager, ApiClient api,
		PlayerState player, ConfigManager configManager, ChatMessageManager chatMessageManager,
		AfkRouletteConfig config, Gson gson)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.api = api;
		this.player = player;
		this.configManager = configManager;
		this.chatMessageManager = chatMessageManager;
		this.config = config;
		this.gson = gson;
	}

	public void addListener(Listener l)
	{
		listeners.add(l);
	}

	/** Progress line for the panel, or null when the category's task isn't auto-tracked. */
	public String progressText(String category)
	{
		Tracked t = tracked.get(category);
		if (t == null)
		{
			return null;
		}
		switch (t.type)
		{
			case "quest":
				return "Completes automatically when the quest is finished.";
			case "diary":
				return "Completes automatically when the diary tier is finished.";
			case "level":
				return String.format("Level %d / %d — tracked automatically", t.progress, t.target);
			case "xp_gain":
				return String.format("%,d / %,d xp — tracked automatically", t.progress, t.target);
			default:
				return String.format("%,d / %,d — tracked automatically", t.progress, t.target);
		}
	}

	/** Re-read every category's active task from the server. Safe from any thread. */
	public void refresh()
	{
		String nick = player.getName();
		if (nick == null || !config.serverEnabled())
		{
			clientThread.invokeLater(() -> tracked.clear());
			return;
		}
		int gen = generation;
		Map<String, String> q = new HashMap<>();
		q.put("nick", nick);
		api.get("/api/afk/current", q, (json, error) ->
		{
			if (error == null)
			{
				boolean done = "done".equals(str(json, "status"));
				JsonObject task = obj(json, "task");
				clientThread.invokeLater(() -> applyIfCurrent(gen, "afk", done ? null : task));
			}
		});
		for (String category : new String[]{"task", "boss", "collection"})
		{
			Map<String, String> cq = new HashMap<>(q);
			cq.put("category", category);
			api.get("/api/tasker/current", cq, (json, error) ->
			{
				if (error == null)
				{
					JsonObject task = obj(json, "active");
					clientThread.invokeLater(() -> applyIfCurrent(gen, category, task));
				}
			});
		}
	}

	/** Forget everything (logout, plugin stop), saving unsaved progress first. Client thread. */
	public void reset()
	{
		saveAll();
		clearState();
	}

	/**
	 * Like {@link #reset()} for a changed RS profile: the config now points at the new profile,
	 * so the old profile's unsaved progress must not be written to it. Client thread.
	 */
	public void resetForNewProfile()
	{
		clearState();
	}

	private void clearState()
	{
		dirty = false;
		ticksSinceSave = 0;
		generation++;
		tracked.clear();
		lastXp.clear();
		lastXpTick.clear();
		pendingGains.clear();
		lastInventory = null;
	}

	/** Take xp baselines so the next xp drop counts. Client thread, logged in. */
	public void baseline()
	{
		for (Skill skill : Skill.values())
		{
			lastXp.put(skill, client.getSkillExperience(skill));
		}
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		lastInventory = inv != null ? countByName(inv) : null;
	}

	/** Called by the plugin with the current quest log (client thread). */
	public void onQuestStates(Map<String, String> states)
	{
		for (Tracked t : tracked.values())
		{
			if ("quest".equals(t.type))
			{
				boolean finished = false;
				for (Map.Entry<String, String> e : states.entrySet())
				{
					if (e.getKey().equalsIgnoreCase(t.quest) && "FINISHED".equals(e.getValue()))
					{
						finished = true;
					}
				}
				if (finished)
				{
					// Finished already the first time we look: done before the roll, so it
					// is marked "already done" instead of earning a completion.
					t.completeAs = t.questChecked ? "done" : "already";
					t.progress = t.target;
					maybeComplete(t);
				}
				if (!t.questChecked)
				{
					t.questChecked = true;
					dirty = true;
				}
			}
		}
	}

	private void applyIfCurrent(int gen, String category, JsonObject task)
	{
		if (gen == generation)
		{
			apply(category, task);
		}
	}

	private void apply(String category, JsonObject task)
	{
		JsonObject verify = obj(task, "verify");
		String name = task != null ? (str(task, "name") != null ? str(task, "name") : str(task, "task")) : null;
		Tracked existing = tracked.get(category);
		if (task == null || verify == null || name == null)
		{
			if (existing != null)
			{
				tracked.remove(category);
				clearProgress(existing);
				notifyListeners(category, false);
			}
			return;
		}
		String rollId = name + "|" + nz(str(task, "rolled"));
		if (existing != null && existing.rollId.equals(rollId))
		{
			// Same roll: nothing to re-read, but retry a completion that failed earlier
			// (levels, diaries and clog items won't produce another event to retry on).
			maybeComplete(existing);
			return;
		}
		if (existing != null)
		{
			// A different roll replaced it (skip, Discord, website...): its progress is void.
			clearProgress(existing);
		}

		Tracked t = new Tracked();
		t.category = category;
		t.name = name;
		t.rollId = rollId;
		t.type = str(verify, "type");
		t.skill = lower(str(verify, "skill"));
		Integer count = integer(verify, "count");
		if (count == null)
		{
			count = integer(verify, "amount");
		}
		if (count == null)
		{
			count = integer(verify, "target");
		}
		t.target = count != null ? count : 1;
		for (String s : strings(verify, "skills"))
		{
			addNonEmpty(t.skills, s);
		}
		for (String s : strings(verify, "names"))
		{
			addNonEmpty(t.names, s);
		}
		for (String s : strings(verify, "items"))
		{
			addNonEmpty(t.names, s);
		}
		t.quest = str(verify, "quest");
		t.region = lower(str(verify, "region"));
		t.tier = lower(str(verify, "tier"));
		if ("clog".equals(t.type) && verify.has("itemIds") && verify.get("itemIds").isJsonArray())
		{
			for (JsonElement id : verify.getAsJsonArray("itemIds"))
			{
				try
				{
					addNonEmpty(t.names, itemManager.getItemComposition(id.getAsInt()).getName());
				}
				catch (RuntimeException e)
				{
					log.debug("Unknown collection log item {}", id, e);
				}
			}
		}
		if (t.type == null || t.target < 1 || !hasUsableSpec(t))
		{
			// An empty name would match every kill or item, so such a spec is never tracked.
			if (existing != null)
			{
				tracked.remove(category);
				notifyListeners(category, false);
			}
			return;
		}
		if ("level".equals(t.type))
		{
			Integer fromLevel = integer(verify, "from");
			int from = fromLevel != null ? fromLevel : t.target - 1;
			t.levelGain = Math.max(1, t.target - from);
			t.levelBase = from;
		}
		boolean restored = restoreProgress(t);
		if ("level".equals(t.type) && t.skill != null)
		{
			Skill skill = skillByName(t.skill);
			if (skill != null && client.getLocalPlayer() != null)
			{
				int real = client.getRealSkillLevel(skill);
				if (!restored)
				{
					// The server's level may be stale (hiscores) or 1 for unranked skills.
					t.levelBase = Math.max(t.levelBase, real);
				}
				t.progress = real;
			}
			t.target = Math.min(99, t.levelBase + t.levelGain);
			dirty = true;
		}
		tracked.put(category, t);
		notifyListeners(category, false);
		maybeComplete(t);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (onIgnoredWorld())
		{
			return;
		}
		Skill skill = event.getSkill();
		String skillName = lower(skill.getName());
		Integer previous = lastXp.put(skill, event.getXp());
		int delta = previous == null ? 0 : event.getXp() - previous;
		if (delta > 0)
		{
			lastXpTick.put(skillName, client.getTickCount());
		}
		for (Tracked t : tracked.values())
		{
			switch (t.type)
			{
				case "xp_actions":
					if (delta > 0 && skillName.equals(t.skill))
					{
						add(t, 1);
					}
					break;
				case "xp_gain":
					if (delta > 0 && t.skills.contains(skillName))
					{
						add(t, delta);
					}
					break;
				case "level":
					if (skillName.equals(t.skill) && event.getLevel() != t.progress)
					{
						t.progress = event.getLevel();
						dirty = true;
						notifyListeners(t.category, false);
						maybeComplete(t);
					}
					break;
				default:
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INV)
		{
			return;
		}
		Map<String, Integer> now = countByName(event.getItemContainer());
		if (lastInventory != null && hasType("item_gain"))
		{
			for (Map.Entry<String, Integer> e : now.entrySet())
			{
				int gained = e.getValue() - lastInventory.getOrDefault(e.getKey(), 0);
				if (gained > 0)
				{
					pendingGains.add(new PendingGain(e.getKey(), gained, client.getTickCount()));
				}
			}
		}
		lastInventory = now;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		// Inventory and xp updates for the same action can arrive in either order;
		// count a gain once its tick is over, if the task's skill gained xp around it.
		int now = client.getTickCount();
		for (Iterator<PendingGain> it = pendingGains.iterator(); it.hasNext(); )
		{
			PendingGain gain = it.next();
			if (gain.tick >= now)
			{
				continue;
			}
			it.remove();
			for (Tracked t : tracked.values())
			{
				if ("item_gain".equals(t.type) && t.names.contains(gain.item))
				{
					Integer xpTick = lastXpTick.get(t.skill);
					if (xpTick != null && xpTick >= gain.tick - 1 && xpTick <= now)
					{
						add(t, gain.qty);
					}
				}
			}
		}

		if (dirty && ++ticksSinceSave >= SAVE_EVERY_TICKS)
		{
			saveAll();
		}
	}

	private void saveAll()
	{
		if (dirty)
		{
			for (Tracked t : tracked.values())
			{
				saveProgress(t);
			}
		}
		ticksSinceSave = 0;
		dirty = false;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (onIgnoredWorld())
		{
			return;
		}
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());

		String counter = null;
		Matcher kc = KC.matcher(message);
		if (kc.find())
		{
			counter = lower(kc.group(1));
		}
		else if (RIFTS.matcher(message).find())
		{
			counter = "guardians of the rift";
		}
		else if (SLAYER.matcher(message).find())
		{
			counter = "slayer task";
		}
		if (counter != null)
		{
			for (Tracked t : tracked.values())
			{
				if ("kc".equals(t.type) && containsAny(counter, t.names))
				{
					add(t, 1);
				}
			}
			return;
		}

		Matcher clog = CLOG.matcher(message);
		if (clog.find())
		{
			String item = lower(clog.group(1));
			for (Tracked t : tracked.values())
			{
				if ("clog".equals(t.type) && t.names.contains(item) && t.seen.add(item))
				{
					t.progress = t.seen.size();
					dirty = true;
					notifyListeners(t.category, false);
					maybeComplete(t);
				}
			}
			return;
		}

		Matcher diary = DIARY.matcher(message);
		if (diary.find())
		{
			String tier = lower(diary.group(1));
			String region = lower(diary.group(2)).replace(" & ", "-and-").replace(' ', '-');
			for (Tracked t : tracked.values())
			{
				if ("diary".equals(t.type) && tier.equals(t.tier) && region.equals(t.region))
				{
					t.progress = t.target;
					dirty = true;
					maybeComplete(t);
				}
			}
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (onIgnoredWorld())
		{
			return;
		}
		if (!(event.getActor() instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) event.getActor();
		Player local = client.getLocalPlayer();
		if (local == null || npc.getName() == null
			|| (local.getInteracting() != npc && npc.getInteracting() != local))
		{
			return;
		}
		String npcName = lower(npc.getName());
		for (Tracked t : tracked.values())
		{
			if ("npc_kill".equals(t.type) && containsAny(npcName, t.names))
			{
				add(t, 1);
			}
		}
	}

	private void add(Tracked t, int amount)
	{
		t.progress += amount;
		dirty = true;
		notifyListeners(t.category, false);
		maybeComplete(t);
	}

	private void maybeComplete(Tracked t)
	{
		if (t.completing || t.progress < t.target || player.getName() == null || onIgnoredWorld()
			|| client.getTickCount() < t.retryAtTick)
		{
			return;
		}
		t.completing = true;
		// Keep the finished progress even if the client closes before the server answers.
		saveProgress(t);
		Map<String, Object> body = new HashMap<>();
		body.put("nick", player.getName());
		body.put("status", t.completeAs);
		// Names the task, so a stale view can't complete whatever is active now.
		body.put("task", t.name);
		String path = "/api/afk/complete";
		if (!"afk".equals(t.category))
		{
			path = "/api/tasker/complete";
			body.put("category", t.category);
		}
		api.post(path, body, (json, error) -> clientThread.invokeLater(() ->
		{
			if (error != null)
			{
				log.debug("Auto-complete failed: {}", error);
				t.completing = false;
				t.retryAtTick = client.getTickCount() + RETRY_TICKS;
				if (json != null)
				{
					// The server answered (e.g. the task changed): re-read what's active.
					refresh();
				}
				return;
			}
			tracked.remove(t.category, t);
			clearProgress(t);
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.runeLiteFormattedMessage(new ChatMessageBuilder()
					.append(ChatColorType.HIGHLIGHT)
					.append("AFK Roulette: ")
					.append(ChatColorType.NORMAL)
					.append("Task complete — " + t.name)
					.build())
				.build());
			notifyListeners(t.category, true);
		}));
	}

	/** @return true when saved progress for this exact roll was found */
	private boolean restoreProgress(Tracked t)
	{
		String raw = configManager.getRSProfileConfiguration(AfkRouletteConfig.GROUP, PROGRESS_KEY + t.category);
		if (raw == null)
		{
			return false;
		}
		try
		{
			JsonObject saved = gson.fromJson(raw, JsonObject.class);
			if (saved != null && t.rollId.equals(str(saved, "roll")))
			{
				Integer savedProgress = integer(saved, "progress");
				t.progress = savedProgress != null ? savedProgress : 0;
				t.seen.addAll(strings(saved, "seen"));
				Integer base = integer(saved, "base");
				if (base != null)
				{
					t.levelBase = base;
				}
				t.questChecked = "true".equals(str(saved, "qc"));
				return true;
			}
		}
		catch (JsonParseException | IllegalStateException | UnsupportedOperationException e)
		{
			log.debug("Bad saved progress for {}", t.category, e);
		}
		return false;
	}

	private void saveProgress(Tracked t)
	{
		JsonObject saved = new JsonObject();
		saved.addProperty("roll", t.rollId);
		saved.addProperty("progress", t.progress);
		saved.addProperty("base", t.levelBase);
		saved.addProperty("qc", t.questChecked);
		JsonArray seen = new JsonArray();
		t.seen.forEach(seen::add);
		saved.add("seen", seen);
		configManager.setRSProfileConfiguration(AfkRouletteConfig.GROUP, PROGRESS_KEY + t.category, gson.toJson(saved));
	}

	private void clearProgress(Tracked t)
	{
		configManager.unsetRSProfileConfiguration(AfkRouletteConfig.GROUP, PROGRESS_KEY + t.category);
	}

	private void notifyListeners(String category, boolean completed)
	{
		for (Listener l : listeners)
		{
			l.onTrackerChanged(category, completed);
		}
	}

	private boolean hasType(String type)
	{
		for (Tracked t : tracked.values())
		{
			if (type.equals(t.type))
			{
				return true;
			}
		}
		return false;
	}

	private Map<String, Integer> countByName(ItemContainer container)
	{
		Map<String, Integer> counts = new HashMap<>();
		for (Item item : container.getItems())
		{
			if (item.getId() > 0 && item.getQuantity() > 0)
			{
				String name = lower(itemManager.getItemComposition(itemManager.canonicalize(item.getId())).getName());
				counts.merge(name, item.getQuantity(), Integer::sum);
			}
		}
		return counts;
	}

	/** Leagues, Deadman etc. must not complete main-game tasks. */
	private boolean onIgnoredWorld()
	{
		return RuneScapeProfileType.getCurrent(client) != RuneScapeProfileType.STANDARD;
	}

	/** Whether the spec has what its type matches on; empty needles would match everything or nothing. */
	private static boolean hasUsableSpec(Tracked t)
	{
		switch (t.type)
		{
			case "kc":
			case "npc_kill":
			case "item_gain":
			case "clog":
				return !t.names.isEmpty();
			case "xp_gain":
				return !t.skills.isEmpty();
			case "xp_actions":
			case "level":
				return t.skill != null && !t.skill.trim().isEmpty();
			case "quest":
				return t.quest != null && !t.quest.trim().isEmpty();
			case "diary":
				return t.tier != null && !t.tier.isEmpty() && t.region != null && !t.region.isEmpty();
			default:
				return true;
		}
	}

	private static void addNonEmpty(Set<String> target, String value)
	{
		String s = lower(value);
		if (s != null && !s.trim().isEmpty())
		{
			target.add(s.trim());
		}
	}

	private static String nz(String s)
	{
		return s == null ? "" : s;
	}

	private static Skill skillByName(String name)
	{
		for (Skill s : Skill.values())
		{
			if (s.getName().equalsIgnoreCase(name))
			{
				return s;
			}
		}
		return null;
	}

	private static boolean containsAny(String text, Set<String> needles)
	{
		for (String n : needles)
		{
			if (text.contains(n))
			{
				return true;
			}
		}
		return false;
	}

	private static String lower(String s)
	{
		return s == null ? null : s.toLowerCase(Locale.ROOT);
	}

	private static String str(JsonObject o, String key)
	{
		if (o == null || !o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive())
		{
			return null;
		}
		return o.get(key).getAsString();
	}

	/** A whole number under {@code key}, or null when it is missing or not numeric. */
	private static Integer integer(JsonObject o, String key)
	{
		if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive())
		{
			return null;
		}
		try
		{
			return o.get(key).getAsInt();
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private static JsonObject obj(JsonObject o, String key)
	{
		if (o == null || !o.has(key) || !o.get(key).isJsonObject())
		{
			return null;
		}
		return o.getAsJsonObject(key);
	}

	private static List<String> strings(JsonObject o, String key)
	{
		List<String> out = new ArrayList<>();
		if (o != null && o.has(key) && o.get(key).isJsonArray())
		{
			for (JsonElement e : o.getAsJsonArray(key))
			{
				if (e.isJsonPrimitive())
				{
					out.add(e.getAsString());
				}
			}
		}
		return out;
	}
}
