package fi.rosu.afkroulette.ui;

import fi.rosu.afkroulette.GroupData;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.components.IconTextField;

/**
 * Search every group member's bank, inventory, worn gear and seed vault at once,
 * e.g. "lobster" lists Lobster and Raw lobster separately per member.
 */
@Singleton
public class ItemsTab extends JPanel
{
	private static final int MAX_RESULTS = 40;
	/** Card text sits next to a 36 px item icon. */
	private static final int CARD_TEXT_WIDTH = Ui.TEXT_WIDTH - 54;
	private static final long STALE_MILLIS = 60_000;
	private static final Map<String, String> CONTAINER_NAMES = new LinkedHashMap<>();

	static
	{
		CONTAINER_NAMES.put("bank", "bank");
		CONTAINER_NAMES.put("inventory", "inv");
		CONTAINER_NAMES.put("equipment", "worn");
		CONTAINER_NAMES.put("seed_vault", "seed vault");
	}

	private final GroupData group;
	private final ItemManager itemManager;
	private final IconTextField search = new IconTextField();
	private final JLabel status = Ui.label("", Ui.MUTED, false);
	private final JPanel results = new JPanel(new DynamicGridLayout(0, 1, 0, 6));

	@Inject
	ItemsTab(GroupData group, ItemManager itemManager)
	{
		this.group = group;
		this.itemManager = itemManager;

		setLayout(new DynamicGridLayout(0, 1, 0, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		search.setIcon(IconTextField.Icon.SEARCH);
		search.setPreferredSize(new Dimension(0, 30));
		search.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		search.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				render();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				render();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				render();
			}
		});
		results.setOpaque(false);

		add(search);
		add(status);
		add(results);
		render();
	}

	public void onShown()
	{
		if (System.currentTimeMillis() - group.getFetchedAtMillis() > STALE_MILLIS)
		{
			status.setForeground(Ui.MUTED);
			status.setText(Ui.wrap("Loading the group's items..."));
			group.refresh(this::render, error ->
			{
				status.setForeground(Ui.ERROR);
				status.setText(Ui.wrap(error));
			});
		}
	}

	private void render()
	{
		results.removeAll();
		String query = search.getText().trim().toLowerCase();
		if (query.length() < 2)
		{
			setStatus("Search the whole group's banks, inventories and gear, e.g. \"lobster\".");
			refreshResults();
			return;
		}

		// item id -> member -> container -> quantity
		Map<Integer, Map<String, Map<String, Long>>> hits = new TreeMap<>();
		for (GroupData.Member m : group.getMembers())
		{
			for (String container : CONTAINER_NAMES.keySet())
			{
				int[] flat = m.items(container);
				for (int i = 0; i + 1 < flat.length; i += 2)
				{
					int id = flat[i];
					int qty = flat[i + 1];
					if (id <= 0 || qty <= 0 || !group.itemName(id).toLowerCase().contains(query))
					{
						continue;
					}
					hits.computeIfAbsent(id, k -> new LinkedHashMap<>())
						.computeIfAbsent(m.getNick(), k -> new LinkedHashMap<>())
						.merge(container, (long) qty, Long::sum);
				}
			}
		}

		if (hits.isEmpty())
		{
			setStatus(group.getMembers().isEmpty()
				? "No group data yet. Make sure the server options are on in the plugin settings."
				: "Nobody in the group has \"" + query + "\".");
			refreshResults();
			return;
		}

		List<Map.Entry<Integer, Map<String, Map<String, Long>>>> sorted = new ArrayList<>(hits.entrySet());
		sorted.sort((a, b) -> Long.compare(total(b.getValue()), total(a.getValue())));
		setStatus(sorted.size() > MAX_RESULTS
			? "Showing the " + MAX_RESULTS + " most common of " + sorted.size() + " matches."
			: sorted.size() + " matching item(s).");
		for (Map.Entry<Integer, Map<String, Map<String, Long>>> e : sorted.subList(0, Math.min(MAX_RESULTS, sorted.size())))
		{
			results.add(itemCard(e.getKey(), e.getValue()));
		}
		refreshResults();
	}

	private JPanel itemCard(int itemId, Map<String, Map<String, Long>> byMember)
	{
		JPanel card = new JPanel(new BorderLayout(6, 0));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(6, 6, 6, 6));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		itemManager.getImage(itemId).addTo(icon);
		card.add(icon, BorderLayout.WEST);

		JPanel text = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
		text.setOpaque(false);
		text.add(Ui.label(group.itemName(itemId) + String.format(" — %,d", total(byMember)), Ui.GOLD, true, CARD_TEXT_WIDTH));
		for (Map.Entry<String, Map<String, Long>> m : byMember.entrySet())
		{
			List<String> parts = new ArrayList<>();
			for (Map.Entry<String, Long> c : m.getValue().entrySet())
			{
				parts.add(CONTAINER_NAMES.get(c.getKey()) + String.format(" %,d", c.getValue()));
			}
			long sum = m.getValue().values().stream().mapToLong(Long::longValue).sum();
			text.add(Ui.label(m.getKey() + String.format(": %,d", sum) + " (" + String.join(", ", parts) + ")",
				ColorScheme.LIGHT_GRAY_COLOR, false, CARD_TEXT_WIDTH));
		}
		card.add(text, BorderLayout.CENTER);
		return card;
	}

	private static long total(Map<String, Map<String, Long>> byMember)
	{
		long sum = 0;
		for (Map<String, Long> containers : byMember.values())
		{
			for (long q : containers.values())
			{
				sum += q;
			}
		}
		return sum;
	}

	private void setStatus(String text)
	{
		status.setForeground(Ui.MUTED);
		status.setText(Ui.wrap(text));
	}

	private void refreshResults()
	{
		results.revalidate();
		results.repaint();
	}
}
