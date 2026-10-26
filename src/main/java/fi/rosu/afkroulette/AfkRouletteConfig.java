package fi.rosu.afkroulette;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(AfkRouletteConfig.GROUP)
public interface AfkRouletteConfig extends Config
{
	String GROUP = "afkroulette";
	String PRIVACY_URL = "https://afk.rosu.fi/privacy.html";
	String SERVER_WARNING = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers";

	@ConfigItem(
		keyName = "serverEnabled",
		name = "Connect to AFK Roulette server",
		description = "Roll tasks and view your group from the AFK Roulette server (afk.rosu.fi). "
			+ "Sends your IP address, your RuneScape name and your task results (completions and skips, "
			+ "derived from xp drops, kill counts, collection log, diary and quest messages). "
			+ "Privacy policy: " + PRIVACY_URL,
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
		description = "Additionally uploads your levels, xp, quest states, inventory, equipment, bank, seed vault, "
			+ "current world and an online heartbeat so your group can see them. Turning this off "
			+ "(or leaving the group) removes your shared data from the group. "
			+ "Privacy policy: " + PRIVACY_URL,
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

	@ConfigSection(
		name = "Discord notifications",
		description = "Post your rare drops, new collection log items and pets to your group's Discord channel",
		position = 10
	)
	String NOTIFY_SECTION = "notifications";

	@ConfigItem(
		keyName = "notifyDiscord",
		name = "Post to the group's Discord",
		description = "Post your rare drops, new collection log items and pets to your group's Discord channel "
			+ "(the channel is set in the panel's Group tab; a group made with /plugin in Discord uses that server's channel). "
			+ "Sends the item, its value, where it came from and optionally a screenshot of your game.",
		warning = SERVER_WARNING,
		section = NOTIFY_SECTION,
		position = 11
	)
	default boolean notifyDiscord()
	{
		return false;
	}

	@ConfigItem(
		keyName = "notifyValue",
		name = "Drop value (gp)",
		description = "Post drops worth at least this much (GE price, or high alch value for untradeables). 0 = off. "
			+ "Chests, clues and raids need RuneLite's Loot Tracker plugin turned on.",
		section = NOTIFY_SECTION,
		position = 12
	)
	default int notifyValue()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "notifyRarity",
		name = "Drop rarity (1/x)",
		description = "Post NPC drops at least this rare, e.g. 512 = 1/512 or rarer (OSRS Wiki drop rates). 0 = off.",
		section = NOTIFY_SECTION,
		position = 13
	)
	default int notifyRarity()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "notifyRequireBoth",
		name = "Require both value and rarity",
		description = "With both limits set, post only drops that pass both (otherwise either one is enough)",
		section = NOTIFY_SECTION,
		position = 14
	)
	default boolean notifyRequireBoth()
	{
		return false;
	}

	@ConfigItem(
		keyName = "notifyUniques",
		name = "Always post uniques (raids, chests, clues)",
		description = "Post every collection log item from raids, Barrows, clues and other chests, whatever its value",
		section = NOTIFY_SECTION,
		position = 15
	)
	default boolean notifyUniques()
	{
		return false;
	}

	@ConfigItem(
		keyName = "notifyCollectionLog",
		name = "New collection log items",
		description = "Post your new collection log items. Needs the in-game setting "
			+ "Collection log - New addition notification (chat message) turned on. "
			+ "The group can also turn these off for everyone in the Group tab.",
		section = NOTIFY_SECTION,
		position = 16
	)
	default boolean notifyCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyPets",
		name = "Pets",
		description = "Post when you get a pet. The group can also turn these off for everyone in the Group tab.",
		section = NOTIFY_SECTION,
		position = 17
	)
	default boolean notifyPets()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyScreenshot",
		name = "Include a screenshot",
		description = "Attach a screenshot of the game to the Discord post. It shows your chatbox too.",
		section = NOTIFY_SECTION,
		position = 18
	)
	default boolean notifyScreenshot()
	{
		return false;
	}

	@ConfigItem(
		keyName = "notifyAlways",
		name = "Always post",
		description = "Item names that are always posted, whatever their value (comma separated)",
		section = NOTIFY_SECTION,
		position = 19
	)
	default String notifyAlways()
	{
		return "";
	}

	@ConfigItem(
		keyName = "notifyNever",
		name = "Never post",
		description = "Item names that are never posted (comma separated)",
		section = NOTIFY_SECTION,
		position = 20
	)
	default String notifyNever()
	{
		return "";
	}
}
