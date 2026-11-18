package dev.amberpath.path;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.Random;

/**
 * Rotation de caméra fluide et RÉALISTE, appliquée à chaque IMAGE (pas à 20 Hz).
 *
 * - Double lissage : la consigne elle-même est filtrée (aucun snap quand le nœud
 *   cible change), puis un lissage exponentiel plafonné en vitesse/accélération.
 * - Micro-mouvements permanents : bruit lent multi-sinusoïdal (±0.7° yaw, ±0.45°
 *   pitch) — la tête n'est jamais figée parfaitement droite.
 * - Légère inclinaison vers le bas en marche (~3°), avec un micro-balancement
 *   synchronisé sur les pas ; quasi neutre à l'arrêt.
 * - Coups d'œil occasionnels (toutes les 4 à 9 s) : petit écart de yaw de 2–5°
 *   qui s'estompe en douceur, comme un joueur qui surveille ses alentours.
 * - Mode « précis » (saut parkour, porte) : bruit réduit, pas de coup d'œil,
 *   inclinaison atténuée — la fiabilité du mouvement prime.
 * - Le tick ne fait que définir la cible ; le HUD appelle frame() à chaque rendu.
 *   Si le HUD est masqué (F1), tickFallback() prend le relais à chaque tick.
 */
public final class AimController {
	private float tYaw, tPitch;            // consigne brute (nœud courant)
	private float sYaw, sPitch;            // consigne filtrée (anti-snap)
	private boolean has, smooth = true, precise;
	private double rate = 9.5, maxRate = 480;
	private long lastNs = 0, lastFrameMs = 0;

	// --- Micro-mouvements / coups d'œil ---
	private final Random rnd = new Random();
	private final double ph1 = rnd.nextDouble() * 6.283, ph2 = rnd.nextDouble() * 6.283, ph3 = rnd.nextDouble() * 6.283;
	private long nextGlanceAt = System.currentTimeMillis() + 2500 + rnd.nextInt(3000);
	private long glanceStart = -1;
	private double glanceDur = 1, glanceYaw = 0, glancePitch = 0;
	private double walkPhase = rnd.nextDouble() * 6.283;

	// --- Détection d'entrée manuelle (souris) ---
	private float lastAppliedYaw, lastAppliedPitch;
	private boolean applied;

	public void setTarget(float yaw, float pitch, boolean smooth, double v, double turnSpeed) {
		if (!has) { sYaw = yaw; sPitch = pitch; }
		tYaw = yaw;
		tPitch = Mth.clamp(pitch, -89f, 89f);
		this.smooth = smooth;
		has = true;
		double ts = Mth.clamp((float) turnSpeed, 0.85f, 1.15f);
		// Courbe progressive : plus rapide quand le joueur court, sans snap brutal.
		rate = Mth.clamp((9.0 + Math.min(v, 1.2) * 2.2) * ts, 8.0, 12.0);
		maxRate = Mth.clamp((420.0 + Math.min(v, 1.2) * 120.0) * ts, 380.0, 570.0);
	}

	/** Mode précis (parkour / porte) : micro-mouvements discrets, aucun coup d'œil. */
	public void setPrecise(boolean precise) { this.precise = precise; }

	public void clear() { has = false; lastNs = 0; applied = false; glanceStart = -1; }

	/** Vrai si la caméra a bougé sans notre intervention (souris du joueur). */
	public boolean externalLook(LocalPlayer p) {
		if (!applied || p == null) return false;
		return Math.abs(Mth.wrapDegrees(p.getYRot() - lastAppliedYaw)) > 1.2f
				|| Math.abs(p.getXRot() - lastAppliedPitch) > 1.2f;
	}

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
		if (!smooth) {
			sYaw = tYaw; sPitch = tPitch;
			applyDirect(p, tYaw, tPitch);
			return;
		}
		if (System.currentTimeMillis() - lastFrameMs > 150) apply(p, 0.05, false);
	}

	private void apply(LocalPlayer p, double dt, boolean snap) {
		long nowMs = System.currentTimeMillis();
		double t = nowMs / 1000.0;
		double v = p.getDeltaMovement().horizontalDistance();

		// 1) Filtre de consigne : suit la cible le long du plus court arc (anti-snap).
		float k = (float) (1.0 - Math.exp(-11.0 * dt));
		sYaw += Mth.wrapDegrees(tYaw - sYaw) * k;
		sPitch += (tPitch - sPitch) * k;

		// 2) Micro-mouvements lents (bruit multi-sinusoïdal, jamais de à-coup).
		double amp = precise ? 0.35 : 1.0;
		double yawNoise = amp * 0.7 * (0.55 * Math.sin(0.6 * t + ph1) + 0.30 * Math.sin(1.7 * t + ph2) + 0.15 * Math.sin(3.9 * t + ph3));
		double pitchNoise = amp * 0.45 * (0.60 * Math.sin(0.5 * t + ph2) + 0.40 * Math.sin(2.3 * t + ph3));

		// 3) Coups d'œil occasionnels : petite déviation qui monte et s'estompe en douceur.
		double gYaw = 0, gPitch = 0;
		if (!precise) {
			if (glanceStart < 0 && nowMs >= nextGlanceAt) {
				glanceStart = nowMs;
				glanceDur = 0.55 + rnd.nextDouble() * 0.75;
				glanceYaw = (rnd.nextBoolean() ? -1 : 1) * (2.0 + rnd.nextDouble() * 3.0);
				glancePitch = (rnd.nextDouble() - 0.5) * 2.0;
				nextGlanceAt = nowMs + (long) (3800 + rnd.nextDouble() * 5200);
			}
			if (glanceStart >= 0) {
				double u = (nowMs - glanceStart) / 1000.0 / glanceDur;
				if (u >= 1) glanceStart = -1;
				else {
					double env = Math.sin(Math.PI * u);
					gYaw = glanceYaw * env;
					gPitch = glancePitch * env;
				}
			}
		} else glanceStart = -1;

		// 4) Inclinaison naturelle : légèrement vers le bas en marche + balancement des pas.
		double bias = 0, bob = 0;
		if (Math.abs(sPitch) < 25) {
			boolean moving = v > 0.08;
			bias = (moving ? 3.2 : 1.2) * (precise ? 0.5 : 1.0);
			if (moving) {
				walkPhase += v * 20.0 * dt * 2.1;
				bob = Math.sin(walkPhase) * 0.4 * (precise ? 0.4 : 1.0);
			}
		}

		float desiredYaw = sYaw + (float) (yawNoise + gYaw);
		float desiredPitch = Mth.clamp(sPitch + (float) (pitchNoise + bias + bob + gPitch), -89f, 89f);

		// 5) Lissage exponentiel final, plafonné en vitesse de rotation.
		float cy = p.getYRot(), cp = p.getXRot();
		float dy = Mth.wrapDegrees(desiredYaw - cy);
		float dp = desiredPitch - cp;
		double kk = 1.0 - Math.exp(-rate * dt);
		float maxStep = (float) (maxRate * dt);
		float sy = Math.abs(dy) < 0.25f ? dy : Mth.clamp((float) (dy * kk), -maxStep, maxStep);
		float sp = Math.abs(dp) < 0.25f ? dp : Mth.clamp((float) (dp * kk), -maxStep, maxStep);
		applyDirect(p, cy + sy, cp + sp);
	}

	private void applyDirect(LocalPlayer p, float ny, float np) {
		np = Mth.clamp(np, -89f, 89f);
		p.setYRot(ny);
		p.setXRot(np);
		// Pas d'interpolation parasite entre ticks : l'ancienne valeur suit la nouvelle.
		p.yRotO = ny;
		p.xRotO = np;
		p.setYHeadRot(ny);
		p.yBodyRot = ny;
		lastAppliedYaw = ny;
		lastAppliedPitch = np;
		applied = true;
	}
}
