# AFK Roulette

A RuneLite plugin for groups that play together (built for a Group Ironman team):

- **Tasks** — roll your daily AFK task (the best xp/h AFK method you can do in a random skill), or a skill, boss or collection log task matched to your levels. Done/Skip are tracked, with streaks and group highscores. Quest tasks respect your real quest log.
- **Group** — see your group members' levels, worn gear and inventory.
- **Items** — search every member's bank, inventory, gear and seed vault at once (e.g. "lobster" shows Lobster and Raw lobster per member).

The same tasks, streaks and highscores are shared with the [AFK Roulette website](https://afk.rosu.fi) and its Discord bot.

## Setup

1. In your group's Discord, run `/plugin` to get the group token.
2. In RuneLite: Configuration → AFK Roulette:
   - turn on **Connect to AFK Roulette server**
   - turn on **Share my data with the group** if you want your group to see your stats and items
   - paste the **Group token**

Both server options are off by default.

## Privacy

With the server options on, the plugin sends your IP address and, if sharing is on, your levels, xp, quest states, inventory, equipment, bank and seed vault to the AFK Roulette server (afk-api.rosu.fi). Only people with your group's token can read the shared data.

## Credits

The group sync's change tracking is adapted from [Group Ironmen Tracker](https://github.com/christoabrown/group-ironmen-tracker) by Christopher Brown (BSD 2-Clause).
