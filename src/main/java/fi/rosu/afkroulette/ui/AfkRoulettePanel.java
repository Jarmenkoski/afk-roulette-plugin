package fi.rosu.afkroulette.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Rectangle;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

@Singleton
public class AfkRoulettePanel extends PluginPanel
{
	private final TasksTab tasksTab;
	private final GroupTab groupTab;

	@Inject
	AfkRoulettePanel(TasksTab tasksTab, GroupTab groupTab, ItemsTab itemsTab)
	{
		super(false);
		this.tasksTab = tasksTab;
		this.groupTab = groupTab;

		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel display = new JPanel(new BorderLayout());
		display.setBackground(ColorScheme.DARK_GRAY_COLOR);
		MaterialTabGroup tabs = new MaterialTabGroup(display);
		tabs.setLayout(new GridLayout(1, 3, 6, 0));
		tabs.setBorder(new EmptyBorder(0, 0, 8, 0));

		MaterialTab tasks = new MaterialTab("Tasks", tabs, scrollable(tasksTab));
		MaterialTab group = new MaterialTab("Group", tabs, scrollable(groupTab));
		MaterialTab items = new MaterialTab("Items", tabs, scrollable(itemsTab));
		group.setOnSelectEvent(() ->
		{
			groupTab.refreshIfStale();
			return true;
		});
		items.setOnSelectEvent(() ->
		{
			itemsTab.onShown();
			return true;
		});
		tabs.addTab(tasks);
		tabs.addTab(group);
		tabs.addTab(items);
		tabs.select(tasks);

		add(tabs, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);
	}

	/** Safe to call from any thread. */
	public void onPlayerChanged()
	{
		SwingUtilities.invokeLater(tasksTab::onPlayerChanged);
	}

	/** Safe to call from any thread. */
	public void onShutdown()
	{
		SwingUtilities.invokeLater(tasksTab::cancelPending);
	}

	/** Safe to call from any thread. */
	public void onConfigChanged()
	{
		SwingUtilities.invokeLater(() ->
		{
			tasksTab.onPlayerChanged();
			groupTab.refresh();
		});
	}

	private static JScrollPane scrollable(JComponent content)
	{
		JPanel top = new WidthTrackingPanel();
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(content, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(top);
		scroll.setBorder(null);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		return scroll;
	}

	/** Always as wide as the sidebar, so nothing inside can push past its edge. */
	private static final class WidthTrackingPanel extends JPanel implements Scrollable
	{
		WidthTrackingPanel()
		{
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return visibleRect.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}
}
