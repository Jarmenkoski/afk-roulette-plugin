package fi.rosu.afkroulette.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.rosu.afkroulette.ApiClient;
import fi.rosu.afkroulette.PlayerState;
import fi.rosu.afkroulette.reel.RollOverlay;
import fi.rosu.afkroulette.tracker.TaskTracker;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.util.LinkBrowser;

/**
 * Roll / Done / Skip for the daily AFK task and the Task, Boss and Collection
 * log generators. Uses the same server endpoints as the website and Discord,
 * so tasks, streaks and highscores are shared everywhere.
 */
@Singleton
public class TasksTab extends JPanel
{
	private static final String WIKI = "https://oldschool.runescape.wiki";
	private static final String QUEST_PREFIX = "Complete the quest: ";

	private enum Category
	{
		AFK("AFK", "afk", "Daily AFK task"),
		TASK("Task", "task", "Skill task"),
		BOSS("Boss", "boss", "Boss task"),
		COLLECTION("Clog", "collection", "Collection log task");

		final String label;
		final String key;
		final String heading;

		Category(String label, String key, String heading)
		{
			this.label = label;
			this.key = key;
			this.heading = heading;
		}
	}

	private final ApiClient api;
	private final PlayerState player;
	private final TaskTracker tracker;
	private final RollOverlay rollOverlay;
	private final Map<Category, JButton> categoryButtons = new EnumMap<>(Category.class);
	private final JLabel title = Ui.label("", Ui.GOLD, true);
	private final JLabel meta = Ui.label("", Ui.MUTED, false);
	private final JLabel tip = Ui.label("", Ui.MUTED, false);
	private final JLabel progress = Ui.label("", Ui.OK, false);
	private final JLabel status = Ui.label("", Ui.MUTED, false);
	private final JButton roll = Ui.button("Roll");
	private final JButton done = Ui.button("Done");
	private final JButton skip = Ui.button("Skip");
	private final JButton already = Ui.button("Already done");

	private Category category = Category.AFK;
	private String wikiUrl;
	private boolean hasTask;
	private boolean taskIsDone;
	private boolean taskIsQuest;
	private boolean busy;
	/** Status to show after the next card reload (e.g. after an automatic completion). */
	private String pendingStatus;
	/** Name of the task on the card, sent with Done/Skip so a stale card can't finish another task. */
	private String shownTaskName;
	/** Bumped when the view changes, so a reel finishing late doesn't show a stale result. */
	private int generation;

	@Inject
	TasksTab(ApiClient api, PlayerState player, TaskTracker tracker, RollOverlay rollOverlay)
	{
		this.api = api;
		this.player = player;
		this.tracker = tracker;
		this.rollOverlay = rollOverlay;
		tracker.addListener((cat, completed) -> SwingUtilities.invokeLater(() -> onTracker(cat, completed)));

		setLayout(new DynamicGridLayout(0, 1, 0, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel categories = new JPanel(new GridLayout(1, 4, 4, 0));
		categories.setOpaque(false);
		for (Category c : Category.values())
		{
			JButton b = Ui.button(c.label);
			b.addActionListener(e -> select(c));
			categoryButtons.put(c, b);
			categories.add(b);
		}
		add(categories);

		JPanel card = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(10, 10, 10, 10));
		title.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (wikiUrl != null)
				{
					LinkBrowser.browse(wikiUrl);
				}
			}
		});
		card.add(title);
		card.add(meta);
		card.add(tip);
		card.add(progress);
		add(card);

		// BorderLayout skips hidden components, so "Already done" leaves no gap when hidden.
		JPanel actions = new JPanel(new BorderLayout(0, 4));
		actions.setOpaque(false);
		roll.setBackground(ColorScheme.BRAND_ORANGE);
		actions.add(roll, BorderLayout.NORTH);
		JPanel finish = new JPanel(new GridLayout(1, 2, 4, 0));
		finish.setOpaque(false);
		finish.add(done);
		finish.add(skip);
		actions.add(finish, BorderLayout.CENTER);
		actions.add(already, BorderLayout.SOUTH);
		add(actions);
		add(status);

		roll.addActionListener(e -> roll());
		done.addActionListener(e -> complete("done"));
		skip.addActionListener(e -> complete("skipped"));
		already.addActionListener(e -> complete("already"));

		select(Category.AFK);
	}

	/** Called when the logged-in character changes (or logs in). */
	public void onPlayerChanged()
	{
		busy = false;
		generation++;
		select(category);
	}

	/** Drops any reply or reel still on its way (plugin shutting down). */
	public void cancelPending()
	{
		generation++;
		busy = false;
		refreshButtons();
	}

	private void select(Category c)
	{
		// A reply always belongs to the category it was requested for.
		if (busy)
		{
			return;
		}
		category = c;
		generation++;
		for (Map.Entry<Category, JButton> e : categoryButtons.entrySet())
		{
			e.getValue().setBackground(e.getKey() == c ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
		}
		roll.setText(c == Category.AFK ? "Today's task" : "Roll");
		clearCard(c == Category.AFK
			? "Your daily AFK task: the best xp/h AFK method in a random skill."
			: "No task yet.");
		setStatus("", Ui.MUTED);
		if (player.getName() != null)
		{
			loadCurrent();
		}
	}

	private void loadCurrent()
	{
		Map<String, String> q = nickQuery();
		Category requested = category;
		String path = "/api/afk/current";
		if (category != Category.AFK)
		{
			path = "/api/tasker/current";
			q.put("category", category.key);
		}
		String target = path;
		request(() -> api.get(target, q, (json, error) -> ui(() ->
		{
			busy = false;
			if (requested != category)
			{
				refreshButtons();
				return;
			}
			if (error != null)
			{
				setStatus(error, Ui.ERROR);
			}
			else if (category == Category.AFK && json.has("task") && json.get("task").isJsonObject())
			{
				showAfk(json);
			}
			else if (json.has("active") && json.get("active").isJsonObject())
			{
				showTask(json.getAsJsonObject("active"));
				setStatus("Your current task — finish or skip it.", Ui.MUTED);
			}
			if (pendingStatus != null)
			{
				setStatus(pendingStatus, Ui.OK);
				pendingStatus = null;
			}
			refreshButtons();
		})));
	}

	private void roll()
	{
		Map<String, String> q = nickQuery();
		String path;
		switch (category)
		{
			case AFK:
				path = "/api/afk/today";
				break;
			case TASK:
				path = "/api/tasker/task/roll";
				break;
			default:
				path = "/api/tasker/roll";
				q.put("tier", category.key);
		}
		setStatus("Rolling...", Ui.MUTED);
		int gen = generation;
		request(() -> api.get(path, q, (json, error) -> ui(() ->
		{
			if (gen == generation)
			{
				showAfterReel(json, error, null, gen);
			}
		})));
	}

	private void complete(String result)
	{
		Map<String, Object> body = new HashMap<>();
		body.put("nick", player.getName());
		body.put("status", result);
		if (shownTaskName != null)
		{
			body.put("task", shownTaskName);
		}
		String path = "/api/tasker/complete";
		if (category == Category.AFK)
		{
			path = "/api/afk/complete";
		}
		else
		{
			body.put("category", category.key);
		}
		String okMessage = "done".equals(result) ? "Task completed!" : null;
		String target = path;
		int gen = generation;
		request(() -> api.post(target, body, (json, error) -> ui(() ->
		{
			if (gen != generation)
			{
				return;
			}
			if (error != null || category == Category.AFK)
			{
				showAfterReel(json, error, okMessage, gen);
				return;
			}
			busy = false;
			tracker.refresh();
			if ("already".equals(result) && json.has("next") && json.get("next").isJsonObject())
			{
				showTask(json.getAsJsonObject("next"));
				setStatus("Marked as already done — here's another quest.", Ui.MUTED);
				refreshButtons();
			}
			else if ("done".equals(result))
			{
				clearCard("Task completed. Roll a new one!");
				setStatus("Task completed!", Ui.OK);
				refreshButtons();
			}
			else
			{
				roll();
			}
		})));
	}

	/** A fresh roll spins the reel first; the card fills in when it stops. */
	private void showAfterReel(JsonObject json, String error, String okMessage, int gen)
	{
		long delay = 0;
		if (error == null && json != null && json.has("reel") && json.get("reel").isJsonArray())
		{
			JsonObject task = json.has("task") && json.get("task").isJsonObject()
				? json.getAsJsonObject("task") : json;
			List<RollOverlay.Icon> reel = new ArrayList<>();
			for (JsonElement e : json.getAsJsonArray("reel"))
			{
				RollOverlay.Icon icon = e.isJsonObject() ? icon(e.getAsJsonObject()) : null;
				if (icon != null)
				{
					reel.add(icon);
				}
			}
			RollOverlay.Icon winner = task.has("icon") && task.get("icon").isJsonObject()
				? icon(task.getAsJsonObject("icon")) : null;
			delay = rollOverlay.play(reel, winner, "AFK Roulette - " + category.heading, nz(str(task, "name")));
		}
		if (delay <= 0)
		{
			showResponse(json, error, okMessage);
			return;
		}
		Timer timer = new Timer((int) delay, e ->
		{
			if (gen == generation)
			{
				showResponse(json, null, okMessage);
			}
		});
		timer.setRepeats(false);
		timer.start();
	}

	private static RollOverlay.Icon icon(JsonObject o)
	{
		if (o.has("item") && o.get("item").isJsonPrimitive())
		{
			return RollOverlay.Icon.item(o.get("item").getAsInt());
		}
		if (o.has("skill") && o.get("skill").isJsonPrimitive())
		{
			return RollOverlay.Icon.skill(o.get("skill").getAsString());
		}
		return null;
	}

	private void showResponse(JsonObject json, String error, String okMessage)
	{
		busy = false;
		if (error != null)
		{
			setStatus(error, Ui.ERROR);
			refreshButtons();
			return;
		}
		tracker.refresh();
		if (category == Category.AFK)
		{
			showAfk(json);
			if (okMessage != null && !taskIsDone)
			{
				setStatus(okMessage + " " + stats(json), Ui.OK);
			}
		}
		else
		{
			JsonObject task = json.has("task") && json.get("task").isJsonObject()
				? json.getAsJsonObject("task") : json;
			showTask(task);
			boolean active = json.has("active") && json.get("active").getAsBoolean();
			setStatus(active ? "Your current task — finish or skip it." : "", Ui.MUTED);
		}
		refreshButtons();
	}

	private void showAfk(JsonObject json)
	{
		if (json == null || !json.has("task") || !json.get("task").isJsonObject())
		{
			clearCard("No task.");
			return;
		}
		JsonObject t = json.getAsJsonObject("task");
		shownTaskName = str(t, "name");
		taskIsDone = "done".equals(str(json, "status"));
		taskIsQuest = false;
		hasTask = true;

		title.setText(Ui.wrap(str(t, "name") + (taskIsDone ? " (done)" : "")));
		List<String> lines = new ArrayList<>();
		String line = capitalize(str(t, "skill"));
		if (t.has("xp") && !t.get("xp").isJsonNull())
		{
			line += String.format(" · ~%,d xp/h", t.get("xp").getAsInt());
		}
		lines.add(line);
		if (str(t, "afk") != null)
		{
			lines.add("AFK ~" + str(t, "afk") + " per click");
		}
		String reqs = reqs(t);
		if (!reqs.isEmpty())
		{
			lines.add("Requires " + reqs);
		}
		meta.setText(Ui.wrap(String.join("\n", lines)).replace("\n", "<br>"));
		tip.setText(Ui.wrap(nz(str(t, "notes"))));
		setWiki(str(t, "url"));
		updateProgress();
		if (taskIsDone)
		{
			setStatus("Done for today! A new AFK task unlocks at midnight (Finnish time). " + stats(json), Ui.OK);
		}
		else
		{
			setStatus(stats(json), Ui.MUTED);
		}
	}

	/** A Task, Boss or Collection log task (fresh roll or the stored current one). */
	private void showTask(JsonObject t)
	{
		String name = str(t, "name") != null ? str(t, "name") : str(t, "task");
		shownTaskName = name;
		taskIsDone = false;
		taskIsQuest = name != null && name.startsWith(QUEST_PREFIX);
		hasTask = true;

		title.setText(Ui.wrap(nz(name)));
		String skill = str(t, "skill");
		String metaText;
		if ("quests".equals(skill))
		{
			metaText = "Quest (" + nz(str(t, "difficulty")) + ")";
		}
		else if (skill != null)
		{
			metaText = capitalize(skill) + " · your level " + nz(str(t, "level")) + " · req " + nz(str(t, "req"));
		}
		else
		{
			String reqs = reqs(t);
			metaText = reqs.isEmpty() ? "" : "Recommended " + reqs;
		}
		meta.setText(Ui.wrap(metaText));
		tip.setText(Ui.wrap(nz(str(t, "tip"))));
		setWiki(str(t, "wiki"));
		updateProgress();
	}

	private void clearCard(String message)
	{
		shownTaskName = null;
		hasTask = false;
		taskIsDone = false;
		taskIsQuest = false;
		title.setText(Ui.wrap(message));
		meta.setText("");
		tip.setText("");
		progress.setText("");
		setWiki(null);
		refreshButtons();
	}

	private void onTracker(String trackerCategory, boolean completed)
	{
		if (!trackerCategory.equals(category.key))
		{
			return;
		}
		if (completed && !busy)
		{
			// The game showed the task done: reload the card from the server.
			pendingStatus = "Task completed automatically!";
			select(category);
		}
		else if (hasTask)
		{
			updateProgress();
		}
	}

	private void updateProgress()
	{
		String text = hasTask && !taskIsDone ? tracker.progressText(category.key) : null;
		progress.setText(text == null ? "" : Ui.wrap(text));
	}

	private void setWiki(String url)
	{
		if (url == null || url.isEmpty())
		{
			wikiUrl = null;
		}
		else
		{
			wikiUrl = url.startsWith("http") ? url : WIKI + url;
		}
		title.setCursor(wikiUrl != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
		title.setToolTipText(wikiUrl != null ? "Open on the OSRS Wiki" : null);
	}

	private void request(Runnable call)
	{
		if (player.getName() == null)
		{
			setStatus("Log in to the game to use tasks.", Ui.ERROR);
			return;
		}
		busy = true;
		refreshButtons();
		call.run();
	}

	private void refreshButtons()
	{
		boolean loggedIn = player.getName() != null;
		for (JButton b : categoryButtons.values())
		{
			b.setEnabled(!busy);
		}
		// One AFK task a day: once it's done there's nothing to roll until midnight.
		roll.setEnabled(loggedIn && !busy && !(category == Category.AFK && taskIsDone));
		done.setEnabled(loggedIn && !busy && hasTask && !taskIsDone);
		skip.setEnabled(loggedIn && !busy && hasTask && !taskIsDone);
		already.setEnabled(loggedIn && !busy && hasTask && taskIsQuest);
		already.setVisible(category == Category.TASK);
	}

	private void setStatus(String text, java.awt.Color color)
	{
		status.setText(text == null || text.isEmpty() ? "" : Ui.wrap(text));
		status.setForeground(color);
	}

	private Map<String, String> nickQuery()
	{
		Map<String, String> q = new HashMap<>();
		q.put("nick", player.getName());
		return q;
	}

	private static void ui(Runnable r)
	{
		SwingUtilities.invokeLater(r);
	}

	private static String stats(JsonObject json)
	{
		if (json == null || !json.has("stats") || !json.get("stats").isJsonObject())
		{
			return "";
		}
		JsonObject s = json.getAsJsonObject("stats");
		return "Streak " + nz(str(s, "current")) + " · Done " + nz(str(s, "done")) + " · Skips " + nz(str(s, "skips"));
	}

	private static String reqs(JsonObject t)
	{
		if (!t.has("reqs") || !t.get("reqs").isJsonObject())
		{
			return "";
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : t.getAsJsonObject("reqs").entrySet())
		{
			parts.add(capitalize(e.getKey()) + " " + e.getValue().getAsString());
		}
		return String.join(", ", parts);
	}

	private static String str(JsonObject o, String key)
	{
		if (o == null || !o.has(key) || o.get(key).isJsonNull())
		{
			return null;
		}
		JsonElement e = o.get(key);
		return e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}

	private static String nz(String s)
	{
		return s == null ? "" : s;
	}

	private static String capitalize(String s)
	{
		if (s == null || s.isEmpty())
		{
			return "";
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
