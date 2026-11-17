package dev.amberpath.path;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Rotation de caméra fluide : lissage exponentiel appliqué à chaque IMAGE (pas à 20 Hz).
 * Le tick ne fait que définir la cible ; le HUD appelle frame() à chaque rendu.
 * Si le HUD est masqué (F1), tickFallback() prend le relais à chaque tick.
 */
public final class AimController {
	private float tYaw, tPitch;
	private boolean has, smooth = true;
	private double rate = 16, maxRate = 900; // 1/s et °/s
	private long lastNs = 0, lastFrameMs = 0;

	public void setTarget(float yaw, float pitch, boolean smooth, double v, double turnSpeed) {
		tYaw = yaw;
		tPitch = Mth.clamp(pitch, -89f, 89f);
		this.smooth = smooth;
		has = true;
		double ts = Math.max(0.2, turnSpeed);
		rate = (16 + v * 60) * ts;
		maxRate = (900 + v * 1500) * ts;
	}

	public void clear() { has = false; lastNs = 0; }

	/** Appelé à chaque image. */
	public void frame(LocalPlayer p) {
		if (!has || p == null || !smooth) return;
		long now = System.nanoTime();
		double dt = lastNs == 0 ? 0.016 : Math.min(0.1, (now - lastNs) / 1e9);
		lastNs = now;
		lastFrameMs = System.currentTimeMillis();
		apply(p, dt, false);
	}

	/** Appelé à chaque tick : snap si lissage désactivé, sinon simple filet de sécurité si aucune image n'a tourné. */
	public void tickFallback(LocalPlayer p) {
		if (!has || p == null) return;
		if (!smooth) { apply(p, 0.05, true); return; }
		if (System.currentTimeMillis() - lastFrameMs > 150) apply(p, 0.05, false);
	}

	private void apply(LocalPlayer p, double dt, boolean snap) {
		float cy = p.getYRot(), cp = p.getXRot();
		float dy = Mth.wrapDegrees(tYaw - cy);
		float dp = tPitch - cp;
		float sy, sp;
		if (snap) { sy = dy; sp = dp; }
		else {
			double k = 1.0 - Math.exp(-rate * dt);
			float maxStep = (float) (maxRate * dt);
			sy = Math.abs(dy) < 0.25f ? dy : Mth.clamp((float) (dy * k), -maxStep, maxStep);
			sp = Math.abs(dp) < 0.25f ? dp : Mth.clamp((float) (dp * k), -maxStep, maxStep);
		}
		float ny = cy + sy;
		float np = Mth.clamp(cp + sp, -89f, 89f);
		p.setYRot(ny);
		p.setXRot(np);
		// Pas d'interpolation parasite entre ticks : l'ancienne valeur suit la nouvelle.
		p.yRotO = ny;
		p.xRotO = np;
		p.setYHeadRot(ny);
		p.yBodyRot = ny;
	}
}