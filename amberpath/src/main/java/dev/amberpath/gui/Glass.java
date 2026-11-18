package dev.amberpath.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/** Moteur de rendu « Liquid Glass » ambré : réfraction, double biseau, caustiques et spécularité. */
public final class Glass {
	// Palette haute lisibilité sur verre translucide
	public static final int CREAM   = 0xFFFDF6EC;
	public static final int MUTED   = 0xFFD4B89B;
	public static final int FAINT   = 0xFFA38569;
	public static final int AMBER   = 0xFFF5B96B;
	public static final int AMBER_D = 0xFFD18432;
	public static final int DARKTXT = 0xFF231105;
	public static final int GREEN   = 0xFFAEE08C;
	public static final int RED     = 0xFFF28B79;

	private Glass() {}

	public static int argb(int a, int rgb) {
		return (Mth.clamp(a, 0, 255) << 24) | (rgb & 0xFFFFFF);
	}

	public static int lerpColor(int c1, int c2, float t) {
		t = Mth.clamp(t, 0f, 1f);
		int a1 = (c1 >>> 24), a2 = (c2 >>> 24);
		int r1 = (c1 >> 16) & 255, r2 = (c2 >> 16) & 255;
		int g1 = (c1 >> 8) & 255, g2 = (c2 >> 8) & 255;
		int b1 = c1 & 255, b2 = c2 & 255;
		return (Math.round(a1 + (a2 - a1) * t) << 24)
				| (Math.round(r1 + (r2 - r1) * t) << 16)
				| (Math.round(g1 + (g2 - g1) * t) << 8)
				| Math.round(b1 + (b2 - b1) * t);
	}

	/** Rectangle arrondi plein. */
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
			int c = lerpColor(top, bottom, h <= 1 ? 0f : (float) dy / (h - 1));
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
			ex.fill(x + inset, y + dy, x + Math.max(inset, inset2 - 1) + 1, y + dy + 1, color);
			ex.fill(x + w - Math.max(inset, inset2 - 1) - 1, y + dy, x + w - inset, y + dy + 1, color);
			ex.fill(x + inset, y + h - 1 - dy, x + Math.max(inset, inset2 - 1) + 1, y + h - dy, color);
			ex.fill(x + w - Math.max(inset, inset2 - 1) - 1, y + h - 1 - dy, x + w - inset, y + h - dy, color);
		}
	}

	/** Biseau optique Liquid Glass : arête lumineuse supérieure + réfraction interne + ombre de base. */
	private static void opticalBevel(GuiGraphicsExtractor ex, int x, int y, int w, int h, int r, int rimAlpha) {
		r = Math.min(r, Math.min(w, h) / 2);
		// 1. Bordure extérieure réfractive (plus claire en haut, ambrée sur les côtés)
		roundOutline(ex, x, y, w, h, r, argb(rimAlpha, 0xFFE6BD));
		// 2. Biseau intérieur sombre (donne l'épaisseur du verre)
		if (w > 4 && h > 4) {
			roundOutline(ex, x + 1, y + 1, w - 2, h - 2, Math.max(1, r - 1), 0x38120702);
		}
		// 3. Reflet spéculaire zénithal (ligne de lumière pure en haut)
		ex.fill(x + r, y, x + w - r, y + 1, argb(Math.min(255, rimAlpha + 75), 0xFFF7E8));
		ex.fill(x + r + 2, y + 1, x + w - r - 2, y + 2, argb(rimAlpha / 2, 0xFFE6BD));
		// 4. Rebonds de lumière au bas du verre (caustique inférieure)
		ex.fill(x + r + 4, y + h - 2, x + w - r - 4, y + h - 1, 0x26F5B96B);
	}

	/** Panneau Liquid Glass principal : ombre ambiante multicouche, corps cristallin translucide et caustiques fluides. */
	public static void glassPanel(GuiGraphicsExtractor ex, int x, int y, int w, int h, int r) {
		// Ombre ambiante douce (3 strates pour éviter les bords durs)
		roundRect(ex, x + 4, y + 7, w, h, r + 2, 0x28000000);
		roundRect(ex, x + 2, y + 4, w, h, r + 1, 0x380A0401);
		roundRect(ex, x + 1, y + 2, w, h, r, 0x45120803);

		// Corps translucide : laisse respirer le flou d'arrière-plan tout en teintant chaudement
		roundRectGradient(ex, x, y, w, h, r, 0x9E4A2E1C, 0xB8261308);

		ex.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		// Halo zénithal diffus (lumière tombant du haut)
		ex.fillGradient(x, y, x + w, y + h / 2, 0x30FFE2B8, 0x00FFE2B8);
		// Contre-jour chaud en bas
		ex.fillGradient(x, y + (h * 2) / 3, x + w, y + h, 0x00E89B48, 0x1CE89B48);

		// Double onde caustique liquide (mouvement organique lent)
		long now = System.currentTimeMillis();
		float t1 = (now % 9000L) / 9000f;
		float t2 = ((now + 4500L) % 13000L) / 13000f;

		int bx1 = x - 100 + (int) ((w + 200) * t1);
		ex.fillGradient(bx1, y, bx1 + 70, y + h, 0x00FFF2DC, 0x14FFF2DC);
		ex.fillGradient(bx1 + 70, y, bx1 + 140, y + h, 0x14FFF2DC, 0x00FFF2DC);

		int bx2 = x + w + 80 - (int) ((w + 160) * t2);
		ex.fillGradient(bx2 - 50, y, bx2, y + h, 0x00F5B96B, 0x0DF5B96B);
		ex.fillGradient(bx2, y, bx2 + 50, y + h, 0x0DF5B96B, 0x00F5B96B);
		ex.disableScissor();

		opticalBevel(ex, x, y, w, h, r, 0x8C);
	}

	/** Carte en verre encapsulée (sous-panneaux). */
	public static void glassCard(GuiGraphicsExtractor ex, int x, int y, int w, int h, int r) {
		roundRect(ex, x + 1, y + 2, w, h, r, 0x26000000);
		roundRectGradient(ex, x, y, w, h, r, 0x7A543622, 0x8C2C170B);
		ex.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		ex.fillGradient(x, y, x + w, y + Math.max(4, h / 2), 0x1CFFE6C4, 0x00FFE6C4);
		ex.disableScissor();
		opticalBevel(ex, x, y, w, h, r, 0x62);
	}

	/** Pilule de sélection (onglets, modes). */
	public static void pill(GuiGraphicsExtractor ex, int x, int y, int w, int h, boolean active, boolean hovered) {
		int r = h / 2;
		if (active) {
			// Goutte d'ambre lumineuse
			roundRect(ex, x, y + 1, w, h, r, 0x33000000);
			roundRectGradient(ex, x, y, w, h, r, 0xF2FAD089, 0xE6D68B3A);
			ex.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
			ex.fillGradient(x, y, x + w, y + h / 2, 0x55FFFFFF, 0x00FFFFFF);
			ex.disableScissor();
			roundOutline(ex, x, y, w, h, r, 0xCCFFF4E0);
			ex.fill(x + r, y + 1, x + w - r, y + 2, 0x99FFFFFF);
		} else {
			int top = hovered ? 0x8C6B472E : 0x5C472D1C;
			int bot = hovered ? 0x99422817 : 0x6E29160C;
			roundRectGradient(ex, x, y, w, h, r, top, bot);
			opticalBevel(ex, x, y, w, h, r, hovered ? 0x85 : 0x48);
		}
	}

	/** Interrupteur liquide avec goutte de verre mobile et lueur ambrée. */
	public static void switchTrack(GuiGraphicsExtractor ex, int x, int y, boolean on, boolean hovered) {
		int w = 32, h = 15, r = 7;
		if (on) {
			roundRectGradient(ex, x, y, w, h, r, 0xEBF5B96B, 0xE0C97A2B);
			roundOutline(ex, x, y, w, h, r, 0xB3FFF0D4);
			ex.fill(x + r, y + 1, x + w - r, y + 2, 0x73FFFFFF);
		} else {
			roundRectGradient(ex, x, y, w, h, r, hovered ? 0x8A3B2314 : 0x7326140A, 0x8A170B05);
			roundOutline(ex, x, y, w, h, r, hovered ? 0x7AD49B63 : 0x48C89058);
		}
		int knobX = on ? x + w - 13 : x + 2;
		// Ombre sous la goutte
		roundRect(ex, knobX, y + 3, 11, 11, 5, 0x45000000);
		// Goutte cristalline
		roundRectGradient(ex, knobX, y + 2, 11, 11, 5, on ? 0xFFFFF8EE : 0xFFE3C8AA, on ? 0xFFE8C9A0 : 0xFFA88768);
		// Reflet spéculaire supérieur sur la goutte
		roundRect(ex, knobX + 2, y + 3, 7, 4, 2, 0x80FFFFFF);
	}

	/** Barre de progression en tube de verre rempli d'ambre liquide. */
	public static void progressBar(GuiGraphicsExtractor ex, int x, int y, int w, int h, float frac) {
		frac = Mth.clamp(frac, 0f, 1f);
		int r = h / 2;
		roundRectGradient(ex, x, y, w, h, r, 0x801A0C05, 0x662B160B);
		roundOutline(ex, x, y, w, h, r, 0x52C89058);
		if (frac > 0.01f) {
			int fw = Math.max(h, (int) (w * frac));
			roundRectGradient(ex, x + 1, y + 1, fw - 2, h - 2, Math.max(1, r - 1), 0xFFFAD089, 0xFFD18432);
			if (fw > 6) {
				ex.fill(x + 3, y + 1, x + fw - 3, y + 2, 0x88FFFFFF);
			}
		}
	}

	/** Petit losange ambré (icône / marqueur). */
	public static void diamond(GuiGraphicsExtractor ex, int cx, int cy, int s, int color) {
		for (int dy = -s; dy <= s; dy++) {
			int half = s - Math.abs(dy);
			ex.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	/** Pastille « effet détecté » en verre dépoli ambré. */
	public static int chip(GuiGraphicsExtractor ex, Font font, int x, int y, String text) {
		int w = font.width(text) + 14;
		roundRectGradient(ex, x, y, w, 14, 7, 0x66F5B96B, 0x45C97A2B);
		opticalBevel(ex, x, y, w, 14, 7, 0x7A);
		ex.text(font, text, x + 7, y + 3, CREAM, true);
		return w;
	}
}