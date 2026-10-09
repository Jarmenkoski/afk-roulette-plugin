package fi.rosu.afkroulette;

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
	/** Told when removing the data failed; runs on the HTTP thread. Set by the UI. */
	private volatile Consumer<String> failureListener;
	private String lastKey;
	private long lastAt;

	@Inject
	GroupLeaver(ApiClient api, PlayerState player)
	{
		this.api = api;
		this.player = player;
	}

	public void setFailureListener(Consumer<String> listener)
	{
		this.failureListener = listener;
	}

	/**
	 * Fire and forget; does nothing while the player's name is unknown. The Leave button and the
	 * config listener may both ask for the same leave, so a repeat within a few seconds is dropped.
	 */
	public void leave(String token)
	{
		String name = player.getName();
		if (name == null || token == null || token.trim().isEmpty())
		{
			return;
		}
		String key = name + '\n' + token.trim();
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
		api.post("/api/plugin/group/leave", body, token.trim(), (json, error) ->
		{
			if (error != null)
			{
				log.debug("Removing shared data from the group failed: {}", error);
				Consumer<String> listener = failureListener;
				if (listener != null)
				{
					listener.accept(error);
				}
			}
		});
	}
}
