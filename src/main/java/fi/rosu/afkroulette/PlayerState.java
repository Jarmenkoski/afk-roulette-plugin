package fi.rosu.afkroulette;

import javax.inject.Singleton;

/**
 * The logged-in player's name, readable from any thread. Written on the client
 * thread by the plugin, read by the panel (EDT) and the uploader.
 */
@Singleton
public class PlayerState
{
	private volatile String name;

	public String getName()
	{
		return name;
	}

	void setName(String name)
	{
		this.name = name;
	}

	/** Game names can contain non-breaking spaces; the server expects plain ones. */
	static String normalize(String rawName)
	{
		return rawName == null ? null : rawName.replace(' ', ' ').trim();
	}
}
