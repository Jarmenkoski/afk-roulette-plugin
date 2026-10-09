package fi.rosu.afkroulette.ui;

import fi.rosu.afkroulette.GroupData;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.util.AsyncBufferedImage;

/** Group members' levels, worn gear and inventory. */
@Singleton
public class GroupTab extends JPanel
{
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

	private final GroupData group;
	private final ItemManager itemManager;
	private final SkillIconManager skillIcons;
	private final JPanel list = new JPanel(new DynamicGridLayout(0, 1, 0, 6));
	private final JLabel status = Ui.label("", Ui.MUTED, false);
	private final JButton refresh = Ui.button("Refresh");
	private final Set<String> expanded = new HashSet<>();

	@Inject
	GroupTab(GroupData group, ItemManager itemManager, SkillIconManager skillIcons)
	{
		this.group = group;
		this.itemManager = itemManager;
		this.skillIcons = skillIcons;

		setLayout(new DynamicGridLayout(0, 1, 0, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.setOpaque(false);
		refresh.addActionListener(e -> refresh());
		add(refresh);
		add(status);
		add(list);
		setStatus("Open this tab to load your group.");
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
		refresh.setEnabled(false);
		setStatus("Loading...");
		group.refresh(() ->
		{
			refresh.setEnabled(true);
			render();
		}, error ->
		{
			refresh.setEnabled(true);
			status.setForeground(Ui.ERROR);
			status.setText(Ui.wrap(error));
		});
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
			JPanel gear = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
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
