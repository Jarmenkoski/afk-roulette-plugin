package fi.rosu.afkroulette;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Experience;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;

/**
 * Latest snapshot of the group's shared data, used by the Group and Items tabs.
 */
@Slf4j
@Singleton
public class GroupData
{
	public static final String[] CONTAINERS = {"inventory", "equipment", "bank", "seed_vault"};

	public static final class Member
	{
		@Getter
		private final String nick;
		/** Seconds since the member's client last uploaded, by the server's clock. */
		@Getter
		private final long secondsAgo;
		@Getter
		private final Map<String, Integer> levels;
		/** container -> flat [id, qty, id, qty, ...] (inventory/equipment keep slot order). */
		private final Map<String, int[]> containers;

		Member(String nick, long secondsAgo, Map<String, Integer> levels, Map<String, int[]> containers)
		{
			this.nick = nick;
			this.secondsAgo = secondsAgo;
			this.levels = levels;
			this.containers = containers;
		}

		public int[] items(String container)
		{
			return containers.getOrDefault(container, new int[0]);
		}

		public int level(String skill)
		{
			return levels.getOrDefault(skill, 1);
		}

		public int totalLevel()
		{
			int total = 0;
			for (int lvl : levels.values())
			{
				total += lvl;
			}
			return total;
		}

		public int combatLevel()
		{
			return Experience.getCombatLevel(level("attack"), level("strength"), level("defence"),
				Math.max(10, level("hitpoints")), level("magic"), level("ranged"), level("prayer"));
		}
	}

	private final ApiClient api;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final Map<Integer, String> names = new ConcurrentHashMap<>();
	private volatile List<Member> members = Collections.emptyList();
	@Getter
	private volatile long fetchedAtMillis;
	@Getter
	private volatile String groupName = "";
	private volatile int requestSeq;

	@Inject
	GroupData(ApiClient api, ClientThread clientThread, ItemManager itemManager)
	{
		this.api = api;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
	}

	public List<Member> getMembers()
	{
		return members;
	}

	/** Drop the cached snapshot, e.g. after leaving a group. */
	public void clear()
	{
		requestSeq++;
		members = Collections.emptyList();
		groupName = "";
		fetchedAtMillis = 0;
	}

	/** Item name from the game cache, or a placeholder until it has been resolved. */
	public String itemName(int itemId)
	{
		return names.getOrDefault(itemId, "Item " + itemId);
	}

	/**
	 * Fetch the group from the server. Both callbacks run on the Swing thread;
	 * onDone runs after every item name in the snapshot has been resolved.
	 */
	public void refresh(Runnable onDone, Consumer<String> onError)
	{
		int seq = ++requestSeq;
		api.get("/api/plugin/group", null, (json, error) ->
		{
			if (seq != requestSeq)
			{
				return; // a newer refresh (or a group switch) superseded this one
			}
			if (error != null)
			{
				SwingUtilities.invokeLater(() -> onError.accept(error));
				return;
			}
			List<Member> parsed;
			try
			{
				parsed = parse(json);
			}
			catch (RuntimeException e)
			{
				log.debug("Bad group data", e);
				SwingUtilities.invokeLater(() -> onError.accept("Could not read the group data"));
				return;
			}
			members = parsed;
			groupName = json.has("name") && !json.get("name").isJsonNull() ? json.get("name").getAsString() : "";
			fetchedAtMillis = System.currentTimeMillis();

			Set<Integer> missing = new HashSet<>();
			for (Member m : parsed)
			{
				for (String c : CONTAINERS)
				{
					int[] flat = m.items(c);
					for (int i = 0; i + 1 < flat.length; i += 2)
					{
						if (flat[i] > 0 && !names.containsKey(flat[i]))
						{
							missing.add(flat[i]);
						}
					}
				}
			}
			if (missing.isEmpty())
			{
				SwingUtilities.invokeLater(onDone);
				return;
			}
			// Item compositions may only be read on the client thread.
			clientThread.invokeLater(() ->
			{
				try
				{
					for (int id : missing)
					{
						// Ids come from other members' clients; one unknown id must not stop the rest.
						try
						{
							names.put(id, itemManager.getItemComposition(id).getName());
						}
						catch (RuntimeException e)
						{
							log.debug("Unknown item id {}", id, e);
						}
					}
				}
				finally
				{
					SwingUtilities.invokeLater(onDone);
				}
			});
		});
	}

	private static List<Member> parse(JsonObject json)
	{
		List<Member> out = new ArrayList<>();
		if (json == null || !json.has("members"))
		{
			return out;
		}
		double now = json.has("now") ? json.get("now").getAsDouble() : System.currentTimeMillis() / 1000.0;
		for (JsonElement el : json.getAsJsonArray("members"))
		{
			JsonObject m = el.getAsJsonObject();
			JsonObject data = m.has("data") ? m.getAsJsonObject("data") : new JsonObject();

			Map<String, Integer> levels = new HashMap<>();
			if (data.has("levels"))
			{
				for (Map.Entry<String, JsonElement> e : data.getAsJsonObject("levels").entrySet())
				{
					levels.put(e.getKey(), e.getValue().getAsInt());
				}
			}

			Map<String, int[]> containers = new HashMap<>();
			for (String c : CONTAINERS)
			{
				if (data.has(c) && data.get(c).isJsonArray())
				{
					JsonArray arr = data.getAsJsonArray(c);
					int[] flat = new int[arr.size()];
					for (int i = 0; i < flat.length; i++)
					{
						flat[i] = arr.get(i).getAsInt();
					}
					containers.put(c, flat);
				}
			}

			double updatedAt = m.has("updated_at") ? m.get("updated_at").getAsDouble() : now;
			long ago = Math.max(0, Math.round(now - updatedAt));
			out.add(new Member(m.get("nick").getAsString(), ago, levels, containers));
		}
		return out;
	}
}
