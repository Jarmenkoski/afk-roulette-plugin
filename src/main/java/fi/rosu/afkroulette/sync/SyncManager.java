package fi.rosu.afkroulette.sync;

import fi.rosu.afkroulette.AfkRouletteConfig;
import fi.rosu.afkroulette.ApiClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Uploads the player's data to the group, sending only the parts that changed
 * since the last successful upload and re-queueing them if an upload fails.
 *
 * The idea of uploading only changed data is the same one Group Ironmen Tracker
 * (https://github.com/christoabrown/group-ironmen-tracker) uses; this is our own
 * implementation.
 */
@Slf4j
@Singleton
public class SyncManager
{
	/** Failed uploads pause the uploader for this many submit rounds. */
	private static final int BACKOFF_ROUNDS = 15;

	private static final class Entry
	{
		String owner;
		Object last;
		Object pending;
	}

	private final Map<String, Entry> entries = new HashMap<>();
	private final ApiClient api;
	private final AfkRouletteConfig config;
	private boolean inFlight;
	private int skipRounds;

	@Inject
	SyncManager(ApiClient api, AfkRouletteConfig config)
	{
		this.api = api;
		this.config = config;
	}

	/**
	 * Record the latest value for a field. Values are plain collections, so
	 * equals() tells whether anything actually changed.
	 */
	public synchronized void update(String owner, String key, Object value)
	{
		if (owner == null || value == null)
		{
			return;
		}
		Entry e = entries.computeIfAbsent(key, k -> new Entry());
		if (!owner.equals(e.owner) || !Objects.equals(value, e.last))
		{
			e.owner = owner;
			e.last = value;
			e.pending = value;
		}
	}

	/** Upload pending changes. Called off the client thread. */
	public void submit(String playerName)
	{
		if (playerName == null || !config.serverEnabled() || !config.shareData()
			|| config.groupToken().trim().isEmpty())
		{
			return;
		}

		Map<String, Object> body = new HashMap<>();
		List<String> taken = new ArrayList<>();
		synchronized (this)
		{
			if (inFlight)
			{
				return;
			}
			if (skipRounds > 0)
			{
				skipRounds--;
				return;
			}
			for (Map.Entry<String, Entry> en : entries.entrySet())
			{
				Entry e = en.getValue();
				// Data captured on another character (account switch) is never sent under this name.
				if (e.pending != null && playerName.equals(e.owner))
				{
					body.put(en.getKey(), e.pending);
					e.pending = null;
					taken.add(en.getKey());
				}
			}
			if (taken.isEmpty())
			{
				return;
			}
			inFlight = true;
		}

		body.put("name", playerName);
		try
		{
			api.post("/api/plugin/update", body, (json, error) -> finish(taken, playerName, error));
		}
		catch (RuntimeException e)
		{
			// The request never left: without this the uploader would stay "in flight" forever.
			finish(taken, playerName, "Request could not be sent");
		}
	}

	/** Ends an upload round; on failure the taken fields are queued again. */
	private synchronized void finish(List<String> taken, String playerName, String error)
	{
		inFlight = false;
		if (error == null)
		{
			return;
		}
		log.debug("Group sync failed: {}", error);
		skipRounds = BACKOFF_ROUNDS;
		for (String key : taken)
		{
			Entry e = entries.get(key);
			if (e != null && e.pending == null && playerName.equals(e.owner))
			{
				e.pending = e.last;
			}
		}
	}

	/** Send everything again, e.g. after the token or the share toggle changes. */
	public synchronized void resendAll()
	{
		for (Entry e : entries.values())
		{
			e.pending = e.last;
		}
		inFlight = false;
		skipRounds = 0;
	}

	public synchronized void reset()
	{
		entries.clear();
		inFlight = false;
		skipRounds = 0;
	}
}
