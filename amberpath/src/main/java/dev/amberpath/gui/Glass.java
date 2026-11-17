package dev.amberpath.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Helpers de rendu « liquid glass » marron/ambré, 100 % procédural (aucune texture). */
public final class Glass {
	// Palette
	public static final int CREAM   = 0xFFF5E6D0;
	public static final int MUTED   = 0xFFB89878;
	public static final int FAINT   = 0xFF8A6F55;
	public static final int AMBER   = 0xFFE8A85C;
	public static final int AMBER_D = 0xFFC77F35;
	public static final int DARKTXT = 0xFF2A1608;
	public static final int GREEN   = 0xFFA8C686;
	public static final int RED     = 0xFFE08573;

	private Glass() {}

	public static int argb(int a, int rgb) { return (a << 24) | (rgb & 0xFFFFFF); }

	public static int lerpColor(int c1, int c2, float t) {
		int a1 = (c1 >>> 24), a2 = (c2 >>> 24);
		int r1 = (c1 >> 16) & 255, r2 = (c2 >> 16) & 255;
		int g1 = (c1 >> 8) & 255, g2 = (c2 >> 8) & 255;
		int b1 = c1 & 255, b2 = c2 & 255;
		int a = Math.round(a1 + (a2 - a1) * t);
		int r = Math.round(r1 + (r2 - r1) * t);
		int g = Math.round(g1 + (g2 - g1) * t);
		int b = Math.round(b1 + (b2 - b1) * t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/** Rectangle arrondi plein (scanlines). */
	public static void roundRect(GuiGraphicsExtractor ex, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) return;
		r = Math.min(r, Math.min(w, h) / 2);
		for (int dy = 0; dy < r; dy++) {
			int inset = r - (int) Math.floor(Math.sqrt((double) r * r - (double) (r - dy) * (r - dy)));
			ex.fill(x + inset, y + dy, x + w - inset, y + dy + 1, color);
			ex.fill(x + inset, y + h - 1 - dy, x + w - inset, y + h - dy, color);
		}
		ex.fill(x, y + r, x + w, y + h - r, color);
	}

	/** Rectangle arrondi avec dégradé vertical. */
	public static void roundRectGradient(GuiGraphicsExtractor ex, int x, int y, int w, int h, int r, int top, int bottom) {
		if (w <= 0 || h <= 0) return;
		r = Math.min(r, Math.min(w, h) / 2);
		for (int dy = 0; dy < h; dy++) {
			int inset = 0;
			if (dy < r) {
				int d = r - dy;
				inset = r - (int) Math.floor(Math.sqrt((double) r * r - (double) d * d));
			} else if (dy >= h - r) {
				int d = r - (h - 1 - dy);
				inset = r - (int) Math.floor(Math.sqrt((double) r * r - (double) d * d));
			}
			int c = lerpColor(top, bottom, h <= 1 ? 0 : (float) dy / (h - 1));
			ex.fill(x + inset, y + dy, x + w - inset, y + dy + 1, c);
		}
	}

	/** Contour arrondi 1 px. */
	public static void roundOutline(GuiGraphicsExtractor ex, int x, int y, int w, int h, int r, int color) {
		if (w <= 2 || h <= 2) return;
		r = Math.min(r, Math.min(w, h) / 2);
		ex.fill(x + r, y, x + w - r, y + 1, color);
		ex.fill(x + r, y + h - 1, x + w - r, y + h, color);
		ex.fill(x, y + r, x + 1, y + h - r, color);
		ex.fill(x + w - 1, y + r, x + w, y + h - r, color);
		for (int dy = 0; dy < r; dy++) {
			int inset = r - (int) Math.floor(Math.sqrt((double) r * r - (double) (r - dy) * (r - dy)));
			int inset2 = r - (int) Math.floor(Math.sqrt((double) r * r - (double) (r - dy - 1) * (r - dy - 1)));
			// coins haut
			ex.fill(x + inset, y + dy, x + Math.max(inset, inset2 - 1) + 1, y + dy + 1, color);
			ex.fill(x + w - Math.max(inset, inset2 - 1) - 1, y + dy, x + w - inset, y + dy + 1, color);
			// coins bas
			ex.fill(x + inset, y + h - 1 - dy, x + Math.max(inset, inset2 - 1) + 1, y + h - dy, color);
			ex.fill(x + w - Math.max(inset, inset2 - 1) - 1, y + h - 1 - dy, x + w - inset, y + h - dy, color);
		}
	}

	/** Panneau verre principal : ombre, dégradé brun translucide, reflet spéculaire, bordure ambrée. */
	public static void glassPanel(GuiGraphicsExtractor ex, int x, int y, int w, int h, int r) {
		roundRect(ex, x + 2, y + 3, w, h, r, 0x50000000);
		roundRect(ex, x + 1, y + 2, w, h, r, 0x38000000);
		roundRectGradient(ex, x, y, w, h, r, 0xD93E2718, 0xD9241208);
		// voile ambré subtil en haut
		ex.enableScissor(x, y, x + w, y + h);
		ex.fillGradient(x, y, x + w, y + Math.max(2, h / 3), 0x2AE8A85C, 0x00E8A85C);
		// reflet liquide animé
		float t = (System.currentTimeMillis() % 7000L) / 7000f;
		int bx = x - 70 + (int) ((w + 140) * t);
		ex.fillGradient(bx, y, bx + 60, y + h, 0x00FFE0B0, 0x0DFFE0B0);
		ex.fillGradient(bx + 60, y, bx + 120, y + h, 0x0DFFE0B0, 0x00FFE0B0);
		ex.disableScissor();
		// liseré supérieur (lumière)
		ex.fill(x + r, y + 1, x + w - r, y + 2, 0x59FFD9A0);
		roundOutline(ex, x, y, w, h, r, 0x7AC89058);
	}

	/** Petit panneau verre (cartes, champs). */
	public static void glassCard(GuiGraphicsExtractor ex, int x, int y, int w, int h, int r) {
		roundRectGradient(ex, x, y, w, h, r, 0xB34A301D, 0xB32E1B0F);
		ex.fill(x + r, y + 1, x + w - r, y + 2, 0x33FFD9A0);
		roundOutline(ex, x, y, w, h, r, 0x59C89058);
	}

	public static void pill(GuiGraphicsExtractor ex, int x, int y, int w, int h, boolean active, boolean hovered) {
		int r = h / 2;
		if (active) {
			roundRectGradient(ex, x, y, w, h, r, 0xFFF0B86E, 0xFFD08F42);
			roundOutline(ex, x, y, w, h, r, 0x80FFE0B0);
		} else {
			roundRectGradient(ex, x, y, w, h, r, hovered ? 0xC85A3B24 : 0x993E2718, hovered ? 0xC8402818 : 0x992A1810);
			roundOutline(ex, x, y, w, h, r, hovered ? 0x8AC89058 : 0x40C89058);
		}
	}

	/** Interrupteur verre. */
	public static void switchTrack(GuiGraphicsExtractor ex, int x, int y, boolean on, boolean hovered) {
		int w = 30, h = 14, r = 7;
		if (on) {
			roundRectGradient(ex, x, y, w, h, r, 0xFFE8A85C, 0xFFC77F35);
			roundOutline(ex, x, y, w, h, r, 0x66FFE0B0);
		} else {
			roundRectGradient(ex, x, y, w, h, r, 0xB3241208, 0xB31A0C05);
			roundOutline(ex, x, y, w, h, r, hovered ? 0x8AC89058 : 0x40C89058);
		}
		int knobX = on ? x + w - 12 : x + 2;
		roundRect(ex, knobX, y + 2, 10, 10, 5, on ? 0xFF3A2416 : 0xFFB89878);
		roundRect(ex, knobX + 1, y + 3, 8, 4, 3, on ? 0x40FFE0B0 : 0x30FFFFFF);
	}

	public static void progressBar(GuiGraphicsExtractor ex, int x, int y, int w, int h, float frac) {
		frac = Math.max(0, Math.min(1, frac));
		roundRect(ex, x, y, w, h, h / 2, 0xB31A0C05);
		roundOutline(ex, x, y, w, h, h / 2, 0x40C89058);
		if (frac > 0.01f) {
			int fw = Math.max(h, (int) (w * frac));
			roundRectGradient(ex, x + 1, y + 1, fw - 2, h - 2, (h - 2) / 2, 0xFFF0B86E, 0xFFC77F35);
		}
	}

	/** Petit losange ambré (icône / marqueur). */
	public static void diamond(GuiGraphicsExtractor ex, int cx, int cy, int s, int color) {
		for (int dy = -s; dy <= s; dy++) {
			int half = s - Math.abs(dy);
			ex.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}
}
