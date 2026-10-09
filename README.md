# AFK Roulette

A RuneLite plugin for groups that play together (built for a Group Ironman team):

- **Tasks** — roll your daily AFK task (the best xp/h AFK method you can do in a random skill), or a skill, boss or collection log task matched to your levels. Most tasks **complete automatically** from what happens in game: xp drops, kill counts, collection log notifications, diary completions, kills and your quest log. Done/Skip are tracked, with streaks and group highscores.
- **Group** — create or join a group, then see your members' levels, worn gear and inventory.
- **Items** — search every member's bank, inventory, gear and seed vault at once (e.g. "lobster" shows Lobster and Raw lobster per member).

The same tasks, streaks and highscores are shared with the [AFK Roulette website](https://afk.rosu.fi) and its Discord bot.

## Setup

1. Configuration → AFK Roulette: turn on **Connect to AFK Roulette server**. Turn on **Share my data with the group** if you want your group to see your stats and items.
2. Open the panel's **Group** tab:
   - **Create group** gives you a token. Use **Copy token** and send it to your group.
   - Everyone else pastes the token and presses **Join group**.

Both server options are off by default. Each group is its own space on the server: only people with your group's token can see its data, and **Leave group** removes your data from it.

### Automatic completion

- Collection log tasks need the in-game setting *Collection log — New addition notification* turned on.
- A few tasks can't be seen from the game (e.g. Sailing, farming runs, some minigames); those keep the Done button.
- Progress is saved per account, so it survives restarting the client.

## Privacy

With the server options on, the plugin sends your IP address and, if sharing is on, your levels, xp, quest states, inventory, equipment, bank and seed vault to the AFK Roulette server (afk-api.rosu.fi). Only people with your group's token can read the shared data.

## Credits

The group sync's change tracking is adapted from [Group Ironmen Tracker](https://github.com/christoabrown/group-ironmen-tracker) by Christopher Brown (BSD 2-Clause). Collection log task data comes from [OSRS-Taskman/task-list](https://github.com/OSRS-Taskman/task-list).
