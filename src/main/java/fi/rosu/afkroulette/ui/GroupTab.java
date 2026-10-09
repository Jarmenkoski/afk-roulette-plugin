package fi.rosu.afkroulette.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.rosu.afkroulette.AfkRouletteConfig;
import fi.rosu.afkroulette.ApiClient;
import fi.rosu.afkroulette.GroupData;
import fi.rosu.afkroulette.PlayerState;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Create / join / leave a group, then see the members' levels, worn gear and
 * inventory. Each group is its own token-protected space on the server.
 */
@Singleton
public class GroupTab extends JPanel
{
	private static final String TOKEN_KEY = "groupToken";

	/** Same order as the in-game skills tab. */
	private static final Skill[] SKILL_ORDER = {
		Skill.ATTACK, Skill.HITPOINTS, Skill.MINING,
		Skill.STRENGTH, Skill.AGILITY, Skill.SMITHING,
		Skill.DEFENCE, Skill.HERBLORE, Skill.FISHING,
		Skill.RANGED, Skill.THIEVING, Skill.COOKING,
		Skill.PRAYER, Skill.CRAFTING, Skill.FIREMAKING,
		Skill.MAGIC, Skill.FLETCHING, Skill.WOODCUTTING,
		Skill.RUNECRAFT, Skill.SLAYER, Skill.FARMING,
		Skill.CONSTRUCTION, Skill.HUNTER, Skill.SAILING,
	};
	private static final long STALE_MILLIS = 60_000;
	private static final int SCORE_ROWS = 5;

	private final GroupData group;
	private final ItemManager itemManager;
	private final SkillIconManager skillIcons;
	private final AfkRouletteConfig config;
	private final ConfigManager configManager;
	private final ApiClient api;
	private final PlayerState player;
	private final JPanel groupBox = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
	private final JPanel list = new JPanel(new DynamicGridLayout(0, 1, 0, 6));
	private final JPanel scores = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
	private final JLabel status = Ui.label("", Ui.MUTED, false);
	private final JButton refresh = Ui.button("Refresh");
	private final Set<String> expanded = new HashSet<>();

	@Inject
	GroupTab(GroupData group, ItemManager itemManager, SkillIconManager skillIcons, AfkRouletteConfig config,
		ConfigManager configManager, ApiClient api, PlayerState player)
	{
		this.group = group;
		this.itemManager = itemManager;
		this.skillIcons = skillIcons;
		this.config = config;
		this.configManager = configManager;
		this.api = api;
		this.player = player;

		setLayout(new DynamicGridLayout(0, 1, 0, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		groupBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupBox.setBorder(new EmptyBorder(8, 8, 8, 8));
		list.setOpaque(false);
		refresh.addActionListener(e -> refresh());
		add(groupBox);
		add(refresh);
		add(status);
		add(list);
		scores.setOpaque(false);
		add(scores);
		rebuildGroupBox();
	}

	public void refreshIfStale()
	{
		if (System.currentTimeMillis() - group.getFetchedAtMillis() > STALE_MILLIS)
		{
			refresh();
		}
	}

	public void refresh()
	{
		rebuildGroupBox();
		if (!inGroup())
		{
			group.clear();
			list.removeAll();
			list.revalidate();
			list.repaint();
			scores.removeAll();
			scores.revalidate();
			scores.repaint();
			refresh.setVisible(false);
			setStatus("");
			return;
		}
		refresh.setVisible(true);
		refresh.setEnabled(false);
		setStatus("Loading...");
		loadScores();
		group.refresh(() ->
		{
			refresh.setEnabled(true);
			if (!inGroup())
			{
				return;
			}
			rebuildGroupBox();
			render();
		}, error ->
		{
			refresh.setEnabled(true);
			status.setForeground(Ui.ERROR);
			status.setText(Ui.wrap(error));
		});
	}

	private boolean inGroup()
	{
		return config.serverEnabled() && !config.groupToken().trim().isEmpty();
	}

	/** The create / join / leave controls on top of the tab. */
	private void rebuildGroupBox()
	{
		groupBox.removeAll();
		if (!config.serverEnabled())
		{
			groupBox.add(Ui.label("Turn on \"Connect to AFK Roulette server\" in the plugin settings "
				+ "to create or join a group.", Ui.MUTED, false, Ui.TEXT_WIDTH - 16));
		}
		else if (config.groupToken().trim().isEmpty())
		{
			buildCreateJoin();
		}
		else
		{
			String name = group.getGroupName();
			groupBox.add(Ui.label("Group: " + (name == null || name.isEmpty() ? "connected" : name),
				Ui.GOLD, true, Ui.TEXT_WIDTH - 16));
			groupBox.add(Ui.label("Share the token with your group so they can join. "
				+ "Only people with the token can see the group's data.", Ui.MUTED, false, Ui.TEXT_WIDTH - 16));
			JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
			row.setOpaque(false);
			JButton copy = Ui.button("Copy token");
			copy.addActionListener(e ->
			{
				Toolkit.getDefaultToolkit().getSystemClipboard()
					.setContents(new StringSelection(config.groupToken().trim()), null);
				copy.setText("Copied!");
			});
			JButton leave = Ui.button("Leave group");
			leave.addActionListener(e -> leaveGroup());
			row.add(copy);
			row.add(leave);
			groupBox.add(row);
		}
		groupBox.revalidate();
		groupBox.repaint();
	}

	private void buildCreateJoin()
	{
		int width = Ui.TEXT_WIDTH - 16;
		groupBox.add(Ui.label("You're not in a group yet.", Ui.GOLD, true, width));

		groupBox.add(Ui.label("Create a new group:", Ui.MUTED, false, width));
		JTextField nameField = field("Group name");
		JButton create = Ui.button("Create group");
		groupBox.add(nameField);
		groupBox.add(create);

		groupBox.add(Ui.label("Or join with a token from your group:", Ui.MUTED, false, width));
		JTextField tokenField = field("Group token");
		JButton join = Ui.button("Join group");
		groupBox.add(tokenField);
		groupBox.add(join);

		JLabel feedback = Ui.label("", Ui.MUTED, false, width);
		groupBox.add(feedback);

		create.addActionListener(e ->
		{
			String name = nameField.getText().trim();
			if (name.isEmpty())
			{
				showFeedback(feedback, "Give the group a name.", Ui.ERROR);
				return;
			}
			create.setEnabled(false);
			Map<String, Object> body = new HashMap<>();
			body.put("name", name);
			api.post("/api/plugin/group/create", body, (json, error) -> SwingUtilities.invokeLater(() ->
			{
				create.setEnabled(true);
				if (error != null)
				{
					showFeedback(feedback, error, Ui.ERROR);
					return;
				}
				// Saving the token fires ConfigChanged, which refreshes this tab.
				configManager.setConfiguration(AfkRouletteConfig.GROUP, TOKEN_KEY, json.get("token").getAsString());
			}));
		});

		join.addActionListener(e ->
		{
			String token = tokenField.getText().trim();
			if (token.isEmpty())
			{
				showFeedback(feedback, "Paste the token you got from your group.", Ui.ERROR);
				return;
			}
			join.setEnabled(false);
			api.get("/api/plugin/group/info", null, token, (json, error) -> SwingUtilities.invokeLater(() ->
			{
				join.setEnabled(true);
				if (error != null)
				{
					showFeedback(feedback, "That token didn't work: " + error, Ui.ERROR);
					return;
				}
				configManager.setConfiguration(AfkRouletteConfig.GROUP, TOKEN_KEY, token);
			}));
		});
	}

	private void leaveGroup()
	{
		int answer = JOptionPane.showConfirmDialog(this,
			"Leave the group? Your shared data is removed from it.\nYou can join again with the token.",
			"Leave group", JOptionPane.YES_NO_OPTION);
		if (answer != JOptionPane.YES_OPTION)
		{
			return;
		}
		String name = player.getName();
		String token = config.groupToken().trim();
		// Forget the token first so the uploader stops, then delete our data with it.
		configManager.unsetConfiguration(AfkRouletteConfig.GROUP, TOKEN_KEY);
		if (name == null)
		{
			return;
		}
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		api.post("/api/plugin/group/leave", body, token, (json, error) ->
		{
			if (error != null)
			{
				SwingUtilities.invokeLater(() ->
				{
					status.setForeground(Ui.ERROR);
					status.setText(Ui.wrap("Left the group, but removing your data failed: " + error
						+ ". Join again with the token and leave once more to retry."));
				});
			}
		});
	}

	private static JTextField field(String tooltip)
	{
		JTextField f = new JTextField();
		f.setToolTipText(tooltip);
		f.setBackground(ColorScheme.DARK_GRAY_COLOR);
		f.setForeground(Color.WHITE);
		f.setCaretColor(Color.WHITE);
		f.setBorder(new EmptyBorder(6, 6, 6, 6));
		f.setPreferredSize(new Dimension(0, 28));
		return f;
	}

	private static void showFeedback(JLabel label, String text, Color color)
	{
		label.setForeground(color);
		label.setText(Ui.wrap(text, Ui.TEXT_WIDTH - 16));
	}

	/** The group's own highscores (the server scopes them by the group token). */
	private void loadScores()
	{
		api.get("/api/leaderboard", null, (afk, afkError) ->
			api.get("/api/tasker/highscores", null, (tasker, taskerError) -> SwingUtilities.invokeLater(() ->
			{
				scores.removeAll();
				if (afkError == null && taskerError == null && inGroup())
				{
					scores.add(Ui.label("Group highscores", Ui.GOLD, true));
					addAfkScores(afk);
					addTaskerScores(tasker, "task", "Task");
					addTaskerScores(tasker, "boss", "Boss");
					addTaskerScores(tasker, "collection", "Collection log");
				}
				scores.revalidate();
				scores.repaint();
			})));
	}

	private void addAfkScores(JsonObject json)
	{
		if (!json.has("players") || json.getAsJsonArray("players").size() == 0)
		{
			return;
		}
		scores.add(Ui.label("AFK (daily)", Ui.MUTED, true));
		int rank = 1;
		for (JsonElement el : json.getAsJsonArray("players"))
		{
			JsonObject p = el.getAsJsonObject();
			scores.add(Ui.label(rank + ". " + p.get("nick").getAsString() + " — streak " + p.get("current").getAsInt()
				+ " · done " + p.get("done").getAsInt() + " · skips " + p.get("skips").getAsInt(),
				ColorScheme.LIGHT_GRAY_COLOR, false));
			if (++rank > SCORE_ROWS)
			{
				break;
			}
		}
	}

	private void addTaskerScores(JsonObject json, String key, String title)
	{
		if (!json.has(key) || json.getAsJsonArray(key).size() == 0)
		{
			return;
		}
		scores.add(Ui.label(title, Ui.MUTED, true));
		int rank = 1;
		for (JsonElement el : json.getAsJsonArray(key))
		{
			JsonObject p = el.getAsJsonObject();
			scores.add(Ui.label(rank + ". " + p.get("nick").getAsString() + " — done " + p.get("done").getAsInt()
				+ " · skips " + p.get("skips").getAsInt(), ColorScheme.LIGHT_GRAY_COLOR, false));
			if (++rank > SCORE_ROWS)
			{
				break;
			}
		}
	}

	private void render()
	{
		List<GroupData.Member> members = group.getMembers();
		list.removeAll();
		if (members.isEmpty())
		{
			setStatus("Nobody has shared data yet. Turn on \"Share my data\" in the plugin settings.");
		}
		else
		{
			setStatus(members.size() + " member(s). Click a name to show details.");
			for (GroupData.Member m : members)
			{
				list.add(memberCard(m));
			}
		}
		list.revalidate();
		list.repaint();
	}

	private JPanel memberCard(GroupData.Member m)
	{
		JPanel card = new JPanel(new BorderLayout());
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(8, 8, 8, 8));

		JPanel header = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
		header.setOpaque(false);
		header.add(Ui.label(m.getNick(), Ui.GOLD, true));
		header.add(Ui.label("Combat " + m.combatLevel() + " · Total " + m.totalLevel()
			+ " · " + Ui.ago(m.getSecondsAgo()), Ui.MUTED, false));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.add(header, BorderLayout.NORTH);

		JPanel details = details(m);
		details.setVisible(expanded.contains(m.getNick()));
		card.add(details, BorderLayout.CENTER);

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				boolean show = !details.isVisible();
				details.setVisible(show);
				if (show)
				{
					expanded.add(m.getNick());
				}
				else
				{
					expanded.remove(m.getNick());
				}
				card.revalidate();
			}
		});
		return card;
	}

	private JPanel details(GroupData.Member m)
	{
		JPanel d = new JPanel(new DynamicGridLayout(0, 1, 0, 6));
		d.setOpaque(false);
		d.setBorder(new EmptyBorder(8, 0, 0, 0));

		JPanel skills = new JPanel(new GridLayout(0, 3, 2, 2));
		skills.setOpaque(false);
		for (Skill skill : SKILL_ORDER)
		{
			JLabel l = new JLabel(String.valueOf(m.level(skill.getName().toLowerCase())));
			l.setIcon(new ImageIcon(skillIcons.getSkillImage(skill, true)));
			l.setForeground(Ui.GOLD);
			l.setToolTipText(skill.getName());
			skills.add(l);
		}
		d.add(skills);

		int[] worn = m.items("equipment");
		if (hasItems(worn))
		{
			d.add(Ui.label("Equipment", Ui.MUTED, false));
			JPanel gear = new JPanel(new GridLayout(0, 4, 2, 2));
			gear.setOpaque(false);
			addItems(gear, worn, false);
			d.add(gear);
		}

		int[] inv = m.items("inventory");
		if (hasItems(inv))
		{
			d.add(Ui.label("Inventory", Ui.MUTED, false));
			JPanel grid = new JPanel(new GridLayout(0, 4, 2, 2));
			grid.setOpaque(false);
			addItems(grid, inv, true);
			d.add(grid);
		}
		return d;
	}

	private void addItems(JPanel target, int[] flat, boolean keepEmptySlots)
	{
		for (int i = 0; i + 1 < flat.length; i += 2)
		{
			int id = flat[i];
			int qty = flat[i + 1];
			if (id <= 0 || qty <= 0)
			{
				if (keepEmptySlots)
				{
					target.add(emptySlot());
				}
				continue;
			}
			target.add(itemIcon(id, qty));
		}
	}

	private JLabel itemIcon(int id, int qty)
	{
		JLabel l = new JLabel();
		l.setPreferredSize(new Dimension(36, 32));
		AsyncBufferedImage img = itemManager.getImage(id, qty, qty > 1);
		img.addTo(l);
		l.setToolTipText(group.itemName(id) + (qty > 1 ? String.format(" x %,d", qty) : ""));
		return l;
	}

	private static JLabel emptySlot()
	{
		JLabel l = new JLabel();
		l.setPreferredSize(new Dimension(36, 32));
		return l;
	}

	private static boolean hasItems(int[] flat)
	{
		for (int i = 0; i + 1 < flat.length; i += 2)
		{
			if (flat[i] > 0 && flat[i + 1] > 0)
			{
				return true;
			}
		}
		return false;
	}

	private void setStatus(String text)
	{
		status.setForeground(Ui.MUTED);
		status.setText(Ui.wrap(text));
	}
}
