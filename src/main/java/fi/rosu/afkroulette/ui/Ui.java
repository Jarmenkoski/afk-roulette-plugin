package fi.rosu.afkroulette.ui;

import java.awt.Color;
import java.awt.Dimension;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/** Shared look for the panel's labels and buttons. */
final class Ui
{
	static final Color GOLD = new Color(245, 197, 66);
	static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR;
	static final Color OK = new Color(76, 175, 80);
	static final Color ERROR = new Color(232, 120, 110);
	/** Usable text width inside a tab (panel width minus padding and scrollbar). */
	static final int TEXT_WIDTH = PluginPanel.PANEL_WIDTH - 40;

	private Ui()
	{
	}

	/** HTML-escaped, word-wrapped label text. */
	static String wrap(String text)
	{
		return wrap(text, TEXT_WIDTH);
	}

	static String wrap(String text, int width)
	{
		String safe = text == null ? "" : text
			.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return "<html><body style='width:" + width + "px'>" + safe + "</body></html>";
	}

	static JLabel label(String text, Color color, boolean bold)
	{
		return label(text, color, bold, TEXT_WIDTH);
	}

	static JLabel label(String text, Color color, boolean bold, int width)
	{
		JLabel l = new JLabel(wrap(text, width));
		l.setForeground(color);
		l.setFont(bold ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont());
		return l;
	}

	static JButton button(String text)
	{
		JButton b = new JButton(text);
		b.setFocusPainted(false);
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(Color.WHITE);
		b.setBorder(new EmptyBorder(6, 4, 6, 4));
		b.setPreferredSize(new Dimension(0, 28));
		return b;
	}

	static String ago(long seconds)
	{
		// Clients send a heartbeat once a minute while logged in.
		if (seconds < 150)
		{
			return "online";
		}
		if (seconds < 3600)
		{
			return (seconds / 60) + " min ago";
		}
		if (seconds < 86400)
		{
			return (seconds / 3600) + " h ago";
		}
		return (seconds / 86400) + " d ago";
	}
}
