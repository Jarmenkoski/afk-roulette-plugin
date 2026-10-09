package fi.rosu.afkroulette;

import fi.rosu.afkroulette.sync.SyncManager;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Removes the player's shared data from a group on the server. The token is
 * passed explicitly because the configured one may already be gone (Leave group)
 * or belong to another group (token changed).
 */
@Slf4j
@Singleton
public class GroupLeaver
{
	private static final long REPEAT_WINDOW_MS = 5_000;

	private final ApiClient api;
	private final PlayerState player;
	private final SyncManager sync;
	/** Told when removing the data failed; runs on the HTTP thread. Set by the UI. */
	private volatile Consumer<String> failureListener;
	private String lastKey;
	private long lastAt;

	@Inject
	GroupLeaver(ApiClient api, PlayerState player, SyncManager sync)
	{
		this.api = api;
		this.player = player;
		this.sync = sync;
	}

	public void setFailureListener(Consumer<String> listener)
	{
		this.failureListener = listener;
	}

	/**
	 * Fire and forget. With {@code keepHistory} only the shared snapshot (levels, items...)
	 * goes and the player's tasks and streaks in the group stay (sharing turned off, token
	 * switched); without it the player leaves the group for good (Leave group). The Leave
	 * button and the config listener may both ask for the same leave, so a repeat within a
	 * few seconds is dropped.
	 */
	public void leave(String token, boolean keepHistory)
	{
		// The name the data was uploaded under, even if another character is logged in now.
		String name = sync.lastUploadedName() != null ? sync.lastUploadedName() : player.getName();
		if (token == null || token.trim().isEmpty())
		{
			return;
		}
		if (name == null)
		{
			notifyFailure("Log in to the game to remove your shared data from the group.");
			return;
		}
		String key = name + '\n' + token.trim() + '\n' + keepHistory;
		synchronized (this)
		{
			long now = System.currentTimeMillis();
			if (key.equals(lastKey) && now - lastAt < REPEAT_WINDOW_MS)
			{
				return;
			}
			lastKey = key;
			lastAt = now;
		}
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		body.put("keep_history", keepHistory);
		// After any upload still on its way, so it can't put the data back.
		sync.runWhenIdle(() -> api.post("/api/plugin/group/leave", body, token.trim(), (json, error) ->
		{
			if (error == null)
			{
				return;
			}
			log.debug("Removing shared data from the group failed: {}", error);
			// The old token was reset on Discord: that group is gone for us anyway.
			if (!error.toLowerCase().contains("invalid group token"))
			{
				notifyFailure("Could not remove your shared data from the group (" + error + ").");
			}
		}));
	}

	private void notifyFailure(String message)
	{
		Consumer<String> listener = failureListener;
		if (listener != null)
		{
			listener.accept(message);
		}
	}
}
