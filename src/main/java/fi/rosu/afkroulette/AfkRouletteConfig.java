package fi.rosu.afkroulette;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(AfkRouletteConfig.GROUP)
public interface AfkRouletteConfig extends Config
{
	String GROUP = "afkroulette";
	String SERVER_WARNING = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers";

	@ConfigItem(
		keyName = "serverEnabled",
		name = "Connect to AFK Roulette server",
		description = "Roll tasks and view your group from the AFK Roulette server (afk.rosu.fi)",
		warning = SERVER_WARNING,
		position = 0
	)
	default boolean serverEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "shareData",
		name = "Share my data with the group",
		description = "Upload your levels, quests, inventory, equipment and bank so your group can see them",
		warning = SERVER_WARNING,
		position = 1
	)
	default boolean shareData()
	{
		return false;
	}

	@ConfigItem(
		keyName = "groupToken",
		name = "Group token",
		description = "Your group's token. Create or join a group in the panel's Group tab (or use /plugin in the AFK Roulette Discord bot).",
		secret = true,
		position = 2
	)
	default String groupToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "rollAnimation",
		name = "Roll animation",
		description = "Spin a reel of icons over the game view when you roll a task",
		position = 3
	)
	default boolean rollAnimation()
	{
		return true;
	}

	@ConfigItem(
		keyName = "rollSound",
		name = "Roll sounds",
		description = "Play ticking and a chime while the roll reel spins (uses the game's sound effect volume)",
		position = 4
	)
	default boolean rollSound()
	{
		return true;
	}
}
