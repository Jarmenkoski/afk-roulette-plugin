package fi.rosu.afkroulette.reel;

import fi.rosu.afkroulette.AfkRouletteConfig;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.SoundEffectID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A case-opening style reel drawn over the game view when a task is rolled:
 * icons slide past a marker, slow down and stop on the rolled task.
 */
@Singleton
public class RollOverlay extends Overlay
{
	/** How long the reel spins; the panel shows the task after this. */
	public static final long SPIN_MS = 5000;
	private static final long HOLD_MS = 3500;
	private static final long FADE_MS = 600;

	private static final int TILE = 52;
	private static final int GAP = 6;
	private static final int STEP = TILE + GAP;
	private static final int VISIBLE = 7;
	private static final int ICON = 40;
	/** Tiles in front of the start position, so the reel is full from the first frame. */
	private static final int LEAD = VISIBLE / 2 + 1;
	/** Index of the rolled task on the reel: enough tiles before it for a long spin. */
	private static final int WINNER_INDEX = LEAD + 30;
	private static final long TICK_SOUND_GAP_MS = 70;
	/** Drawn at up to twice the base size, shrunk to fit narrow (fixed mode) viewports. */
	private static final double MAX_SCALE = 2.0;
	private static final int VIEWPORT_MARGIN = 16;
	private static final int MAX_RESULT_CHARS = 100;
	private static final int MAX_ITEM_ID = 65535;

	private static final Color BACKGROUND = new Color(20, 18, 15, 225);
	private static final Color TILE_COLOR = new Color(58, 52, 44);
	private static final Color GOLD = new Color(245, 197, 66);

	private final Client client;
	private final ItemManager itemManager;
	private final SkillIconManager skillIconManager;
	private final AfkRouletteConfig config;

	/** Set from the Swing thread, cleared on the client thread when the reel has faded. */
	private final AtomicReference<Spin> spin = new AtomicReference<>();

	/** One reel icon: an item or a skill. */
	public static final class Icon
	{
		final int item;
		final String skill;

		private Icon(int item, String skill)
		{
			this.item = item;
			this.skill = skill;
		}

		public static Icon item(int id)
		{
			return new Icon(id, null);
		}

		public static Icon skill(String name)
		{
			return new Icon(0, name);
		}
	}

	private static final class Spin
	{
		final List<Icon> tiles;
		final String heading;
		final String result;
		final long start;
		/** Where the marker stops inside the winning tile, so it doesn't always land dead centre. */
		final int stopOffset;
		final Map<Icon, BufferedImage> images = new HashMap<>();
		int lastTile = -1;
		long lastSound;
		boolean winSoundPlayed;

		Spin(List<Icon> tiles, String heading, String result)
		{
			this.tiles = tiles;
			this.heading = heading;
			this.result = result;
			this.start = System.currentTimeMillis();
			this.stopOffset = ThreadLocalRandom.current().nextInt(-TILE / 3, TILE / 3 + 1);
		}
	}

	@Inject
	RollOverlay(Client client, ItemManager itemManager, SkillIconManager skillIconManager, AfkRouletteConfig config)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.skillIconManager = skillIconManager;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	/**
	 * Spin the reel and stop on {@code winner}. Returns how long to wait before
	 * showing the result, or 0 when the reel is off or there is nothing to spin.
	 */
	public long play(List<Icon> reel, Icon winner, String heading, String result)
	{
		if (!config.rollAnimation() || !valid(winner))
		{
			return 0;
		}
		// The reel comes from the server: only icons the game can actually have are drawn.
		List<Icon> usable = new ArrayList<>();
		for (Icon icon : reel)
		{
			if (valid(icon))
			{
				usable.add(icon);
			}
		}
		if (usable.isEmpty())
		{
			return 0;
		}
		List<Icon> tiles = new ArrayList<>();
		for (int i = 0; i < WINNER_INDEX + VISIBLE; i++)
		{
			tiles.add(i == WINNER_INDEX ? winner : usable.get(i % usable.size()));
		}
		String text = result == null ? "" : result.length() > MAX_RESULT_CHARS ? result.substring(0, MAX_RESULT_CHARS) : result;
		spin.set(new Spin(tiles, heading, text));
		return SPIN_MS;
	}

	private static boolean valid(Icon icon)
	{
		return icon != null && (icon.skill != null || (icon.item >= 1 && icon.item <= MAX_ITEM_ID));
	}

	public void stop()
	{
		spin.set(null);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Spin s = spin.get();
		if (s == null)
		{
			return null;
		}
		long t = System.currentTimeMillis() - s.start;
		if (t > SPIN_MS + HOLD_MS + FADE_MS)
		{
			// A reel started meanwhile must not be cleared.
			spin.compareAndSet(s, null);
			return null;
		}

		double p = Math.min(1.0, t / (double) SPIN_MS);
		double eased = 1 - Math.pow(1 - p, 4);
		double startPos = LEAD * STEP;
		double distance = (WINNER_INDEX - LEAD) * STEP + s.stopOffset;
		double pos = startPos + distance * eased;
		boolean stopped = p >= 1.0;
		playSounds(s, pos, stopped);

		Composite oldComposite = g.getComposite();
		long fadeAt = SPIN_MS + HOLD_MS;
		if (t > fadeAt)
		{
			float alpha = Math.max(0f, 1f - (t - fadeAt) / (float) FADE_MS);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		int width = VISIBLE * STEP + GAP;
		int height = TILE + 70;
		double scale = Math.max(0.5, Math.min(MAX_SCALE,
			(client.getViewportWidth() - 2 * VIEWPORT_MARGIN) / (double) width));
		double screenX = client.getViewportXOffset() + (client.getViewportWidth() - width * scale) / 2;
		double screenY = Math.max(client.getViewportYOffset() + VIEWPORT_MARGIN,
			client.getViewportYOffset() + client.getViewportHeight() / 4.0 - height * scale / 2);
		AffineTransform oldTransform = g.getTransform();
		g.translate(screenX, screenY);
		g.scale(scale, scale);
		// From here on everything is drawn at base size with the box's top-left at (0, 0).
		int centerX = width / 2;
		int x = 0;
		int y = 0;

		g.setColor(BACKGROUND);
		g.fillRoundRect(x, y, width, height, 10, 10);
		g.setColor(GOLD);
		g.setStroke(new BasicStroke(2));
		g.drawRoundRect(x, y, width, height, 10, 10);

		g.setFont(FontManager.getRunescapeBoldFont());
		drawCentered(g, s.heading, centerX, y + 18, GOLD);

		int stripY = y + 26;
		Shape oldClip = g.getClip();
		g.clipRect(x + 2, stripY, width - 4, TILE);
		int first = Math.max(0, (int) Math.floor((pos - width / 2.0) / STEP) - 1);
		int last = Math.min(s.tiles.size() - 1, (int) Math.ceil((pos + width / 2.0) / STEP) + 1);
		for (int i = first; i <= last; i++)
		{
			int tileX = (int) Math.round(centerX + i * STEP - pos - TILE / 2.0);
			boolean winner = stopped && i == WINNER_INDEX;
			g.setColor(winner ? GOLD.darker() : TILE_COLOR);
			g.fillRect(tileX, stripY, TILE, TILE);
			if (winner)
			{
				g.setColor(GOLD);
				g.drawRect(tileX, stripY, TILE - 1, TILE - 1);
			}
			BufferedImage img = image(s, s.tiles.get(i));
			if (img != null)
			{
				double fitIcon = Math.min(ICON / (double) img.getWidth(), ICON / (double) img.getHeight());
				int w = (int) Math.round(img.getWidth() * fitIcon);
				int h = (int) Math.round(img.getHeight() * fitIcon);
				g.drawImage(img, tileX + (TILE - w) / 2, stripY + (TILE - h) / 2, w, h, null);
			}
		}
		g.setClip(oldClip);

		// The marker the reel stops under.
		g.setColor(GOLD);
		g.fillPolygon(new int[]{centerX - 7, centerX + 7, centerX}, new int[]{stripY - 6, stripY - 6, stripY + 4}, 3);
		g.fillPolygon(new int[]{centerX - 7, centerX + 7, centerX}, new int[]{stripY + TILE + 6, stripY + TILE + 6, stripY + TILE - 4}, 3);
		g.drawLine(centerX, stripY, centerX, stripY + TILE);

		if (stopped)
		{
			g.setFont(FontManager.getRunescapeBoldFont());
			drawCentered(g, fit(g, s.result, width - 16), centerX, stripY + TILE + 24, Color.WHITE);
		}
		g.setTransform(oldTransform);
		g.setComposite(oldComposite);
		return null;
	}

	private void playSounds(Spin s, double pos, boolean stopped)
	{
		// playSoundEffect(id) would play even with game sounds muted; follow the player's volume.
		int volume = client.getPreferences().getSoundEffectVolume();
		if (!config.rollSound() || volume <= 0)
		{
			return;
		}
		int tile = (int) Math.floor((pos + STEP / 2.0) / STEP);
		long now = System.currentTimeMillis();
		if (!stopped && tile != s.lastTile && now - s.lastSound >= TICK_SOUND_GAP_MS)
		{
			client.playSoundEffect(SoundEffectID.UI_BOOP, volume);
			s.lastSound = now;
		}
		s.lastTile = tile;
		if (stopped && !s.winSoundPlayed)
		{
			s.winSoundPlayed = true;
			client.playSoundEffect(SoundEffectID.GE_ADD_OFFER_DINGALING, volume);
		}
	}

	private BufferedImage image(Spin s, Icon icon)
	{
		if (s.images.containsKey(icon))
		{
			return s.images.get(icon);
		}
		BufferedImage img = null;
		if (icon.skill != null)
		{
			Skill skill = skill(icon.skill);
			if (skill != null)
			{
				img = skillIconManager.getSkillImage(skill);
			}
		}
		else if (icon.item > 0)
		{
			img = itemManager.getImage(icon.item);
		}
		s.images.put(icon, img);
		return img;
	}

	private static Skill skill(String name)
	{
		String wanted = "combat".equalsIgnoreCase(name) ? "attack" : name;
		for (Skill skill : Skill.values())
		{
			if (skill.getName().equalsIgnoreCase(wanted))
			{
				return skill;
			}
		}
		return null;
	}

	private static void drawCentered(Graphics2D g, String text, int centerX, int baseline, Color color)
	{
		FontMetrics fm = g.getFontMetrics();
		int x = centerX - fm.stringWidth(text) / 2;
		g.setColor(Color.BLACK);
		g.drawString(text, x + 1, baseline + 1);
		g.setColor(color);
		g.drawString(text, x, baseline);
	}

	private static String fit(Graphics2D g, String text, int maxWidth)
	{
		FontMetrics fm = g.getFontMetrics();
		if (fm.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		String cut = text;
		while (cut.length() > 1 && fm.stringWidth(cut + "...") > maxWidth)
		{
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + "...";
	}
}
