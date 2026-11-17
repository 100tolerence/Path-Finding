package dev.amberpath.gui;

import dev.amberpath.AmberPathClient;
import dev.amberpath.config.AmberConfig;
import dev.amberpath.path.Navigator;
import dev.amberpath.path.PathEngine;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Écran principal « liquid glass » marron : navigation, réglages, touches. */
public class AmberPathScreen extends Screen {
	private static final int PW = 470, PH = 336;
	private int px, py, tab = 0;
	private final AmberConfig cfg = AmberPathClient.CONFIG;
	private Navigator nav() { return AmberPathClient.NAV; }

	private Widgets.GlassField fieldX, fieldY, fieldZ;
	private Widgets.GlassButton startBtn;
	private Widgets.GlassSlider radiusSlider;
	private final List<AbstractWidget> navW = new ArrayList<>(), setW = new ArrayList<>(), keyW = new ArrayList<>();
	private Widgets.KeyBindButton capturing;
	private KeyMapping captureTarget;
	private String errorMsg = ""; private long errorAt = 0;

	public AmberPathScreen() { super(Component.literal("AmberPath")); }

	private static class Pill extends AbstractWidget {
		private final BooleanSupplier sel; private final Runnable click;
		Pill(int x, int y, int w, int h, String label, BooleanSupplier sel, Runnable click) {
			super(x, y, w, h, Component.literal(label)); this.sel = sel; this.click = click;
		}
		@Override protected void extractWidgetRenderState(GuiGraphicsExtractor ex, int mx, int my, float d) {
			boolean a = sel.getAsBoolean();
			Glass.pill(ex, getX(), getY(), getWidth(), getHeight(), a, isHovered());
			ex.centeredText(Minecraft.getInstance().font, getMessage(), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, a ? Glass.DARKTXT : Glass.CREAM);
		}
		@Override public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
			if (active && visible && e.button() == 0 && isMouseOver(e.x(), e.y())) {
				playButtonClickSound(Minecraft.getInstance().getSoundManager()); click.run(); return true;
			}
			return false;
		}
		@Override protected void updateWidgetNarration(NarrationElementOutput out) { out.add(NarratedElementType.TITLE, getMessage()); }
	}

	@Override
	protected void init() {
		px = (width - PW) / 2; py = (height - PH) / 2;
		navW.clear(); setW.clear(); keyW.clear();

		addRenderableWidget(new Widgets.GlassButton(px + PW - 34, py + 12, 22, 22, "✕", false, false, this::onClose));
		String[] tabs = {"Navigation", "Réglages", "Touches"};
		for (int i = 0; i < 3; i++) {
			final int t = i;
			addRenderableWidget(new Pill(px + 24 + i * 118, py + 42, 110, 20, tabs[i], () -> tab == t, () -> setTab(t)));
		}

		// --- Onglet Navigation ---
		fieldX = new Widgets.GlassField(px + 24, py + 80, 128, 26, "X");
		fieldY = new Widgets.GlassField(px + 171, py + 80, 128, 26, "Y · auto");
		fieldZ = new Widgets.GlassField(px + 318, py + 80, 128, 26, "Z");
		fieldX.setValue(cfg.lastX); fieldY.setValue(cfg.lastY); fieldZ.setValue(cfg.lastZ);
		navW.add(fieldX); navW.add(fieldY); navW.add(fieldZ);
		addRenderableWidget(fieldX); addRenderableWidget(fieldY); addRenderableWidget(fieldZ);

		String[] modes = {"Se tenir dessus", "Atteindre", "Approcher"};
		int[] mw = {116, 78, 86}, mxx = {px + 24, px + 146, px + 230};
		for (int i = 0; i < 3; i++) {
			final int m = i;
			navW.add(addRenderableWidget(new Pill(mxx[i], py + 118, mw[i], 20, modes[i], () -> cfg.goalMode == m, () -> { cfg.goalMode = m; cfg.save(); })));
		}
		radiusSlider = new Widgets.GlassSlider(px + 324, py + 112, 122, "Rayon", 1, 8, 1, "", () -> (double) cfg.nearRadius, v -> { cfg.nearRadius = (int) (double) v; cfg.save(); });
		navW.add(addRenderableWidget(radiusSlider));

		startBtn = new Widgets.GlassButton(px + 24, py + 150, PW - 48, 30, "▶  Démarrer la navigation", true, false, this::startNav);
		navW.add(addRenderableWidget(startBtn));
		navW.add(addRenderableWidget(new Widgets.GlassButton(px + 24, py + 284, 110, 22, "Ma position", false, false, this::fillPos)));
		navW.add(addRenderableWidget(new Widgets.GlassButton(px + 140, py + 284, 80, 22, "Effacer", false, false, () -> { fieldX.setValue(""); fieldY.setValue(""); fieldZ.setValue(""); })));

		// --- Onglet Réglages ---
		int c1 = px + 24, c2 = px + 244, cw = 202, y0 = py + 74;
		String[][] togLabels = {
				{"Sprint", "Parkour (sauts)", "Diagonales", "Nage", "Échelles & lianes", "Portes automatiques"},
				{"Chutes", "Éviter les dangers", "Sneak sur magma", "Pause en menu", "Pause si dégâts", "Replanification auto"}
		};
		BooleanSupplier[] g1 = {() -> cfg.sprint, () -> cfg.parkour, () -> cfg.diagonals, () -> cfg.swim, () -> cfg.climb, () -> cfg.doors};
		java.util.function.Consumer<Boolean>[] s1 = null;
		List<java.util.function.Consumer<Boolean>> sl1 = List.of(
				v -> cfg.sprint = v, v -> cfg.parkour = v, v -> cfg.diagonals = v, v -> cfg.swim = v, v -> cfg.climb = v, v -> cfg.doors = v);
		BooleanSupplier[] g2 = {() -> cfg.descend, () -> cfg.avoidDanger, () -> cfg.sneakMagma, () -> cfg.pauseInMenus, () -> cfg.pauseOnDamage, () -> cfg.autoReplan};
		List<java.util.function.Consumer<Boolean>> sl2 = List.of(
				v -> cfg.descend = v, v -> cfg.avoidDanger = v, v -> cfg.sneakMagma = v, v -> cfg.pauseInMenus = v, v -> cfg.pauseOnDamage = v, v -> cfg.autoReplan = v);
		for (int i = 0; i < 6; i++) {
			setW.add(addRenderableWidget(new Widgets.GlassToggle(c1, y0 + i * 24, cw, togLabels[0][i], g1[i], sl1.get(i))));
			setW.add(addRenderableWidget(new Widgets.GlassToggle(c2, y0 + i * 24, cw, togLabels[1][i], g2[i], sl2.get(i))));
		}
		setW.add(addRenderableWidget(new Widgets.GlassSlider(px + 24, py + 226, PW - 48, "Chute maximale", 3, 20, 1, " blocs", () -> (double) cfg.maxFall, v -> { cfg.maxFall = (int) (double) v; cfg.save(); })));
		setW.add(addRenderableWidget(new Widgets.GlassSlider(px + 24, py + 254, PW - 48, "Écart parkour maximal", 1, 4, 1, " blocs", () -> (double) cfg.maxGap, v -> { cfg.maxGap = (int) (double) v; cfg.save(); })));
		setW.add(addRenderableWidget(new Widgets.GlassSlider(px + 24, py + 282, PW - 48, "Recherche : optimale ↔ rapide", 1.0, 1.6, 0.05, "", () -> cfg.weight, v -> { cfg.weight = v; cfg.save(); })));

		// --- Onglet Touches ---
		Widgets.KeyBindButton openBind = new Widgets.KeyBindButton(px + 300, py + 80, 146, 24,
				() -> AmberPathClient.OPEN_KEY.getTranslatedKeyMessage().getString(), () -> beginCapture(AmberPathClient.OPEN_KEY));
		Widgets.KeyBindButton toggleBind = new Widgets.KeyBindButton(px + 300, py + 116, 146, 24,
				() -> AmberPathClient.TOGGLE_KEY.getTranslatedKeyMessage().getString(), () -> beginCapture(AmberPathClient.TOGGLE_KEY));
		keyW.add(addRenderableWidget(openBind)); keyW.add(addRenderableWidget(toggleBind));
		keyW.add(addRenderableWidget(new Widgets.GlassButton(px + 24, py + 160, 200, 24, "Réinitialiser (G / V)", false, false, () -> {
			AmberPathClient.OPEN_KEY.setKey(InputConstants.getKey("key.keyboard.g"));
			AmberPathClient.TOGGLE_KEY.setKey(InputConstants.getKey("key.keyboard.v"));
			minecraft.options.save();
		})));

		setTab(0);
	}

	private void beginCapture(KeyMapping mapping) {
		if (capturing != null) capturing.capturing = false;
		captureTarget = mapping;
		for (var w : keyW) if (w instanceof Widgets.KeyBindButton b) { b.capturing = false; }
		// retrouve le bouton cliqué via focus : on marque les deux, un seul recevra la touche
		for (var w : keyW) if (w instanceof Widgets.KeyBindButton b) {
			if ((mapping == AmberPathClient.OPEN_KEY && w.getY() == py + 80) || (mapping == AmberPathClient.TOGGLE_KEY && w.getY() == py + 116)) {
				b.capturing = true; capturing = b;
			}
		}
	}

	private void setTab(int t) {
		tab = t;
		for (var w : navW) w.visible = t == 0;
		for (var w : setW) w.visible = t == 1;
		for (var w : keyW) w.visible = t == 2;
		if (t != 2 && capturing != null) { capturing.capturing = false; capturing = null; }
	}

	private void fillPos() {
		if (minecraft.player == null) return;
		var bp = minecraft.player.blockPosition();
		fieldX.setValue(String.valueOf(bp.getX()));
		fieldY.setValue(String.valueOf(bp.getY()));
		fieldZ.setValue(String.valueOf(bp.getZ()));
	}

	private Integer parseCoord(String s, boolean allowNull) {
		s = s.trim();
		if (s.isEmpty()) return allowNull ? null : null;
		if (minecraft.player != null && s.startsWith("~")) {
			int base = (int) Math.floor(minecraft.player.getX());
			if (s.length() > 1) { try { return base + Integer.parseInt(s.substring(1)); } catch (NumberFormatException e) { return null; } }
			return base;
		}
		try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
	}

	private void startNav() {
		Navigator nav = nav();
		if (nav.active()) { nav.stop(); nav.msg("Navigation §carrêtée§r."); return; }
		Integer x = parseCoord(fieldX.getValue(), false);
		Integer z = parseCoord(fieldZ.getValue(), false);
		Integer y = parseCoord(fieldY.getValue(), true);
		if (x == null || z == null) {
			errorMsg = "Coordonnées X / Z invalides."; errorAt = System.currentTimeMillis();
			if (minecraft.player != null) minecraft.player.playSound(SoundEvents.VILLAGER_NO, 0.6f, 1f);
			return;
		}
		cfg.lastX = fieldX.getValue(); cfg.lastY = fieldY.getValue(); cfg.lastZ = fieldZ.getValue();
		cfg.save();
		nav.start(new PathEngine.Goal(cfg.goalMode, x, y, z, cfg.nearRadius));
		minecraft.setScreenAndShow(null);
	}

	@Override public boolean isPauseScreen() { return false; }

	@Override public void tick() {
		startBtn.setMessage(Component.literal(nav().active() ? "■  Arrêter la navigation" : "▶  Démarrer la navigation"));
		radiusSlider.visible = tab == 0 && cfg.goalMode == 2;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (capturing != null) {
			if (e.key() == InputConstants.KEY_ESCAPE) { capturing.capturing = false; capturing = null; return true; }
			InputConstants.Key k = InputConstants.getKey(e);
			if (k != InputConstants.UNKNOWN) {
				captureTarget.setKey(k);
				minecraft.options.save();
				capturing.capturing = false; capturing = null;
				if (minecraft.player != null) minecraft.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5f, 1.6f);
			}
			return true;
		}
		if (e.key() == InputConstants.KEY_RETURN && tab == 0) { startNav(); return true; }
		return super.keyPressed(e);
	}

	@Override public boolean charTyped(CharacterEvent e) { return super.charTyped(e); }
	@Override public boolean mouseClicked(MouseButtonEvent e, boolean dbl) { return super.mouseClicked(e, dbl); }

	@Override
	public void extractRenderState(GuiGraphicsExtractor ex, int mx, int my, float delta) {
		this.extractBackground(ex, mx, my, delta);
		ex.blurBeforeThisStratum();
		ex.nextStratum();
		var font = minecraft.font;

		Glass.glassPanel(ex, px, py, PW, PH, 14);
		// En-tête
		Glass.diamond(ex, px + 28, py + 24, 7, Glass.AMBER);
		Glass.diamond(ex, px + 28, py + 24, 3, Glass.DARKTXT);
		ex.text(font, "AmberPath", px + 42, py + 15, Glass.CREAM, true);
		ex.text(font, "Navigation intelligente · liquid glass", px + 42, py + 27, Glass.MUTED, false);
		ex.fill(px + 16, py + 66, px + PW - 16, py + 67, 0x33C89058);

		if (tab == 0) {
			ex.text(font, "DESTINATION", px + 24, py + 70, Glass.FAINT, false);
			// Carte d'état
			int sx = px + 24, sy = py + 192, sw = PW - 48, sh = 80;
			Glass.glassCard(ex, sx, sy, sw, sh, 9);
			Navigator nav = nav();
			String line1, line2 = ""; int dot = Glass.MUTED;
			switch (nav.status) {
				case COMPUTING -> { line1 = "Calcul du chemin en cours…"; dot = Glass.AMBER; line2 = nav.lastExpansions + " nœuds explorés"; }
				case RUNNING -> {
					line1 = "En route vers la destination"; dot = Glass.GREEN;
					line2 = String.format("%.0f m restants · ~%d s · %d nœuds", nav.remainingDistance(), Math.max(1, (int) (nav.remainingTicks() / 20)), nav.pathNodes());
				}
				case PAUSED -> { line1 = "En pause (" + nav.pauseReason + ")"; dot = Glass.MUTED; }
				case ARRIVED -> { line1 = "Arrivé à destination ✓"; dot = Glass.GREEN; }
				case FAILED -> { line1 = "Aucun chemin trouvé"; dot = Glass.RED; line2 = "Objectif inaccessible ou hors chunks chargés."; }
				default -> { line1 = "Prêt à naviguer"; line2 = "Entrez X / Z (Y vide = hauteur auto), puis démarrez."; }
			}
			Glass.roundRect(ex, sx + 12, sy + 13, 6, 6, 3, dot);
			ex.text(font, line1, sx + 24, sy + 12, Glass.CREAM, true);
			if (!line2.isEmpty()) ex.text(font, line2, sx + 12, sy + 28, Glass.MUTED, false);
			if (nav.status == Navigator.Status.RUNNING && nav.pathNodes() > 1) {
				Glass.progressBar(ex, sx + 12, sy + 46, sw - 24, 6, (float) nav.progressIdx() / nav.pathNodes());
				String pc = (int) (100f * nav.progressIdx() / nav.pathNodes()) + " %";
				ex.text(font, pc, sx + sw - 12 - font.width(pc), sy + 58, Glass.AMBER, true);
			} else if (nav.status == Navigator.Status.COMPUTING) {
				Glass.progressBar(ex, sx + 12, sy + 46, sw - 24, 6, 0.5f + 0.4f * Mth.sin((System.currentTimeMillis() % 1200) / 1200f * 6.28f));
			}
			ex.text(font, "Astuce : « ~ » = coordonnée actuelle. Entrée pour démarrer.", sx + 12, sy + sh - 14, Glass.FAINT, false);
		} else if (tab == 2) {
			ex.text(font, "RACCOURCIS CLAVIER", px + 24, py + 70, Glass.FAINT, false);
			ex.text(font, "Ouvrir l'interface", px + 24, py + 88, Glass.CREAM, true);
			ex.text(font, "Activer / arrêter la navigation", px + 24, py + 124, Glass.CREAM, true);
			ex.textWithWordWrap(font, Component.literal("Cliquez sur une touche puis appuyez sur la nouvelle combinaison. Échap pour annuler. Ces raccourcis sont aussi disponibles dans Options → Contrôles → AmberPath."), px + 24, py + 204, PW - 48, Glass.MUTED);
			if (capturing != null) ex.text(font, "Appuyez sur une touche…", px + 24, py + 250, Glass.AMBER, true);
		}

		// Widgets
		for (var c : children()) {
			if (c instanceof AbstractWidget w && !w.visible) continue;
			if (c instanceof Renderable r) r.extractRenderState(ex, mx, my, delta);
		}

		// Pied de page
		ex.text(font, "v1.0.0", px + 24, py + PH - 16, Glass.FAINT, false);
		String fv = "Fabric · Minecraft 26.3";
		ex.text(font, fv, px + PW - 24 - font.width(fv), py + PH - 16, Glass.FAINT, false);

		// Toast d'erreur
		if (System.currentTimeMillis() - errorAt < 2500 && !errorMsg.isEmpty()) {
			int tw = font.width(errorMsg) + 24;
			int tx = (width - tw) / 2, ty = py + PH + 8;
			Glass.glassCard(ex, tx, ty, tw, 20, 8);
			ex.text(font, errorMsg, tx + 12, ty + 6, Glass.RED, true);
		}
	}
}
