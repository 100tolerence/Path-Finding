package dev.amberpath.hud;

import dev.amberpath.AmberPathClient;
import dev.amberpath.gui.Glass;
import dev.amberpath.path.Navigator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

/** HUD verre : état, distance, ETA, boussole et marqueur d'objectif projeté. */
public class AmberHud implements HudElement {
	@Override
	public void extractRenderState(GuiGraphicsExtractor ex, DeltaTracker dt) {
		var cfg = AmberPathClient.CONFIG;
		Navigator nav = AmberPathClient.NAV;
		if (nav != null) nav.frameAim();
		if (!cfg.showHud || nav == null) return;
		boolean show = nav.active() || (nav.status == Navigator.Status.ARRIVED && System.currentTimeMillis() - nav.arrivedAt < 4000)
				|| nav.status == Navigator.Status.FAILED;
		if (!show || nav.goal == null) return;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null) return;
		var font = mc.font;
		float partial = dt.getGameTimeDeltaPartialTick(true);

		// Carte d'état
		int x = 6, y = 6, w = 182, h = 46;
		Glass.glassCard(ex, x, y, w, h, 9);
		String label; int dot;
		switch (nav.status) {
			case COMPUTING -> { label = "Calcul du chemin…"; dot = Glass.AMBER; }
			case RUNNING -> { label = "En route"; dot = Glass.GREEN; }
			case PAUSED -> { label = "Pause · " + nav.pauseReason; dot = Glass.MUTED; }
			case ARRIVED -> { label = "Arrivé ✓"; dot = Glass.GREEN; }
			case FAILED -> { label = "Échec du chemin"; dot = Glass.RED; }
			default -> { label = "Prêt"; dot = Glass.MUTED; }
		}
		if (nav.status == Navigator.Status.COMPUTING && (System.currentTimeMillis() / 400) % 2 == 0) dot = Glass.AMBER_D;
		Glass.roundRect(ex, x + 9, y + 10, 6, 6, 3, dot);
		ex.text(font, label, x + 20, y + 9, Glass.CREAM, true);
		String dest = "→ " + nav.goal.x + ", " + (nav.goal.y == null ? "auto" : nav.goal.y) + ", " + nav.goal.z;
		ex.text(font, dest, x + 9, y + 22, Glass.MUTED, true);
		if (nav.status == Navigator.Status.RUNNING) {
			double d = nav.remainingDistance();
			double s = nav.remainingTicks() / 20.0;
			String info = String.format("%.0f m · ~%ds · ×%.1f", d, Math.max(1, (int) s), nav.lastSpeedFactor);
			ex.text(font, info, x + w - 9 - font.width(info), y + 22, Glass.AMBER, true);
			float frac = nav.pathNodes() > 1 ? (float) nav.progressIdx() / nav.pathNodes() : 0;
			Glass.progressBar(ex, x + 9, y + 36, w - 18, 5, frac);
		} else if (nav.status == Navigator.Status.COMPUTING) {
			String n = nav.lastExpansions > 0 ? nav.lastExpansions + " nœuds" : "exploration…";
			ex.text(font, n, x + 9, y + 34, Glass.FAINT, true);
		}

		if (nav.status != Navigator.Status.RUNNING && nav.status != Navigator.Status.COMPUTING) return;

		// Boussole vers la cible
		int W = ex.guiWidth(), H = ex.guiHeight();
		double tx = nav.goal.x + 0.5, tz = nav.goal.z + 0.5;
		float targetYaw = (float) Math.toDegrees(Math.atan2(-(tx - p.getX()), tz - p.getZ()));
		float rel = Mth.wrapDegrees(targetYaw - p.getYRot());
		int cw = 132, cx0 = W / 2 - cw / 2, cy0 = 6;
		Glass.glassCard(ex, cx0, cy0, cw, 15, 7);
		String[] cards = {"N", "E", "S", "O"};
		float[] cardYaw = {180, -90, 0, 90}; // yaw MC : 0=sud, 90=ouest, 180=nord, -90=est
		for (int i = 0; i < 4; i++) {
			float r = Mth.wrapDegrees(cardYaw[i] - p.getYRot());
			if (Math.abs(r) < 85) {
				int mx = W / 2 + (int) (r / 85f * (cw / 2 - 8));
				ex.text(font, cards[i], mx - font.width(cards[i]) / 2, cy0 + 4, Glass.FAINT, false);
			}
		}
		int marker = W / 2 + (int) (Mth.clamp(rel, -85, 85) / 85f * (cw / 2 - 8));
		Glass.diamond(ex, marker, cy0 + 7, 3, Glass.AMBER);

		// Marqueur d'objectif projeté à l'écran
		int gy = nav.goal.y == null ? (int) Math.floor(p.getY()) : nav.goal.y;
		double wx = tx, wy = gy + 1.2, wz = tz;
		double exX = Mth.lerp(partial, p.xo, p.getX());
		double exY = Mth.lerp(partial, p.yo, p.getY()) + p.getEyeHeight();
		double exZ = Mth.lerp(partial, p.zo, p.getZ());
		double ddx = wx - exX, ddy = wy - exY, ddz = wz - exZ;
		double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
		// Au loin, on masque le marqueur projeté : évite un repère trompeur
		// et garde le HUD propre pendant les longues traversées.
		if (dist > 128) return;
		float yaw = p.getViewYRot(partial), pitch = p.getViewXRot(partial);
		double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
		double fx = -Math.sin(yr) * Math.cos(pr), fy = -Math.sin(pr), fz = Math.cos(yr) * Math.cos(pr);
		double rx = -Math.cos(yr), rz = -Math.sin(yr);
		double ux = rz * fy - 0 * fz, uy = 0 * fx - rx * fz, uz = rx * fy - rz * fx; // right × forward
		double cz = ddx * fx + ddy * fy + ddz * fz;
		int fov = mc.options.fov().get();
		double focal = (H / 2.0) / Math.tan(Math.toRadians(fov) / 2.0);
		if (cz > 0.2) {
			double cx = ddx * rx + ddz * rz;
			double cy = ddx * ux + ddy * uy + ddz * uz;
			int sx = W / 2 + (int) (cx / cz * focal);
			int sy = H / 2 - (int) (cy / cz * focal);
			if (sx > 8 && sx < W - 8 && sy > 24 && sy < H - 8) {
				Glass.diamond(ex, sx, sy, 4, 0xDDF0B86E);
				Glass.diamond(ex, sx, sy, 2, 0xFF2A1608);
				String ds = String.format("%.0f m", dist);
				ex.text(font, ds, sx - font.width(ds) / 2, sy + 7, Glass.CREAM, true);
			}
		}
	}
}