package dev.amberpath.path;

import dev.amberpath.config.AmberConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Suit le chemin en simulant les entrées clavier vanilla. Robuste à TRES haute vitesse
 * (tolérances et sauts prédictifs proportionnels à la vélocité live, « pure pursuit »
 * qui vise le nœud suivant dans les virages pour ne pas casser l'élan), aux chutes
 * (contrôle en l'air, anti-dépassement, garde santé avant chute risquée, récupération
 * immédiate du chemin après atterrissage) et au recul : « saute » les nœuds déjà
 * dépassés au lieu de perdre le chemin. Ignore les mobs.
 *
 * Skyblock : course sur l'eau (sprint forcé, jamais de sneak, coup de saut si on
 * s'enfonce), auto-step assisté d'un saut client si le serveur tarde à monter.
 */
public class PathExecutor {
	public enum Signal { NONE, PATH_END, STUCK, BLOCKED, FALL_UNSAFE }

	private final Minecraft mc;
	private final AmberConfig cfg;
	private final WorldModel model;
	private final PathEngine.PathResult path;
	public int idx = 1;
	public boolean stuckByCollision = false;
	public final AimController aim = new AimController();
	private int stuckTicks = 0, collisionTicks = 0, doorCooldown = 0, validateTimer = 0;
	private double bestDist = Double.MAX_VALUE;
	private boolean wasAirborne = false;

	public PathExecutor(Minecraft mc, AmberConfig cfg, WorldModel model, PathEngine.PathResult path) {
		this.mc = mc; this.cfg = cfg; this.model = model; this.path = path;
	}

	public BlockPos target() { return idx < path.nodes.size() ? path.nodes.get(idx) : null; }
	public boolean currentWaterRun() {
		return idx - 1 >= 0 && idx - 1 < path.moves.size() && path.moves.get(idx - 1).waterRun;
	}

	public void release() {
		aim.clear();
		var o = mc.options;
		o.keyUp.setDown(false); o.keyDown.setDown(false); o.keyLeft.setDown(false);
		o.keyRight.setDown(false); o.keyJump.setDown(false); o.keyShift.setDown(false); o.keySprint.setDown(false);
	}

	private double speed(LocalPlayer p) { return p.getDeltaMovement().horizontalDistance(); }

	/** Avance idx au-delà des nœuds atteints ou dépassés (anti-dépassement haute vitesse / chute). */
	private void skipAhead(LocalPlayer p) {
		Vec3 f = p.position();
		double v = speed(p);
		double latTol = 0.7 + v * 0.35;
		double reach = Math.max(0.5, v * 0.95);
		double vertTol = Math.max(0.9, v * 0.6 + 0.4);
		int j = idx;
		int limit = Math.min(path.nodes.size(), idx + 12);
		while (j < limit) {
			BlockPos n = path.nodes.get(j);
			int k = model.kind(n.getX(), n.getY(), n.getZ());
			double tf = k == WorldModel.GROUND || k == WorldModel.SURFACE ? model.floorOf(k, n.getX(), n.getY(), n.getZ()) : n.getY();
			if (Double.isNaN(tf)) tf = n.getY();
			double dx = n.getX() + 0.5 - f.x, dz = n.getZ() + 0.5 - f.z;
			double d2 = Math.hypot(dx, dz);
			boolean near = (k == WorldModel.GROUND || k == WorldModel.SURFACE)
					? d2 < reach && Math.abs(f.y - tf) < vertTol
					: Math.sqrt(d2 * d2 + (tf - f.y) * (tf - f.y)) < Math.max(0.65, v * 0.9);
			if (near) { j++; continue; }
			if (j > idx) {
				BlockPos prev = path.nodes.get(j - 1);
				double dirx = n.getX() - prev.getX(), dirz = n.getZ() - prev.getZ();
				double len = Math.hypot(dirx, dirz);
				if (len > 0) {
					dirx /= len; dirz /= len;
					double rx = f.x - (n.getX() + 0.5), rz = f.z - (n.getZ() + 0.5);
					double along = rx * dirx + rz * dirz;
					double lat = Math.abs(rx * (-dirz) + rz * dirx);
					if (along > 0.1 && lat < latTol && Math.abs(f.y - tf) < vertTol + 0.6) { j++; continue; }
				}
			}
			break;
		}
		if (j > idx) { idx = j; stuckTicks = 0; bestDist = Double.MAX_VALUE; }
	}

	/** Après une chute : retrouve le premier nœud du chemin proche de la position réelle. */
	private int recoverAfterFall(Vec3 feet) {
		int limit = Math.min(path.nodes.size(), idx + 64);
		for (int j = idx; j < limit; j++) {
			BlockPos n = path.nodes.get(j);
			int k = model.kind(n.getX(), n.getY(), n.getZ());
			double tf = k == WorldModel.GROUND || k == WorldModel.SURFACE ? model.floorOf(k, n.getX(), n.getY(), n.getZ()) : n.getY();
			if (Double.isNaN(tf)) tf = n.getY();
			double d = Math.hypot(n.getX() + 0.5 - feet.x, n.getZ() + 0.5 - feet.z);
			if (d < 2.3 && Math.abs(feet.y - tf) < 2.8) return j;
		}
		return -1;
	}

	public Signal tick() {
		LocalPlayer p = mc.player;
		if (p == null) return Signal.NONE;
		if (idx >= path.nodes.size()) { release(); return Signal.PATH_END; }

		skipAhead(p);
		if (idx >= path.nodes.size()) { release(); return Signal.PATH_END; }

		// Validation périodique (blocs posés/cassés, portes, lave si Résistance au feu perdue)
		if (++validateTimer >= 8) {
			validateTimer = 0;
			for (int j = idx; j < Math.min(idx + 4, path.nodes.size()); j++) {
				BlockPos n = path.nodes.get(j);
				if (model.kind(n.getX(), n.getY(), n.getZ()) == WorldModel.INVALID) { release(); return Signal.BLOCKED; }
			}
		}

		BlockPos t = path.nodes.get(idx);
		PathEngine.Move mv = path.moves.get(idx - 1);
		int tk = model.kind(t.getX(), t.getY(), t.getZ());
		if (tk == WorldModel.INVALID) tk = WorldModel.GROUND;
		if (tk == WorldModel.LAVA && !p.hasEffect(MobEffects.FIRE_RESISTANCE)) { release(); return Signal.BLOCKED; }
		double tf = model.floorOf(tk, t.getX(), t.getY(), t.getZ());
		if (Double.isNaN(tf)) tf = t.getY();

		Vec3 feet = p.position();
		double v = speed(p);
		double tx = t.getX() + 0.5, tz = t.getZ() + 0.5;
		double dx = tx - feet.x, dz = tz - feet.z;
		double dist2 = Math.hypot(dx, dz);
		double dy = tf - feet.y;
		double dist3 = Math.sqrt(dx * dx + dy * dy + dz * dz);

		boolean advanced = (tk == WorldModel.GROUND || tk == WorldModel.SURFACE)
				? dist2 < Math.max(0.42, v * 0.85) && Math.abs(feet.y - tf) < Math.max(0.75, v * 0.5 + 0.3)
				: dist3 < Math.max(0.65, v * 0.85);
		if (advanced) { idx++; stuckTicks = 0; bestDist = Double.MAX_VALUE; return Signal.NONE; }

		boolean onGround = p.onGround();
		boolean inWater = p.isInWater();

		// --- Garde anti-void / récupération de chute ---
		if (!onGround && p.getDeltaMovement().y < -0.02) {
			if (feet.y < mc.level.getMinY() + 1) { release(); return Signal.STUCK; }
			if (feet.y < tf - 3.0) {
				int rec = recoverAfterFall(feet);
				if (rec > 0) { idx = rec; stuckTicks = 0; bestDist = Double.MAX_VALUE; release(); return Signal.NONE; }
				if (feet.y < tf - 6.0) { release(); return Signal.STUCK; } // replanification immédiate
			}
		}
		if (wasAirborne && onGround) {
			int rec = recoverAfterFall(feet);
			if (rec > idx) idx = rec;
			stuckTicks = 0; bestDist = Double.MAX_VALUE;
		}
		wasAirborne = !onGround;

		// Porte / portillon à ouvrir
		if (mv.door && model.doorClosed(t.getX(), t.getY(), t.getZ())) {
			if (dist3 <= 2.6) {
				aim(p, tx, t.getY() + 0.5, tz, true);
				if (--doorCooldown <= 0) {
					doorCooldown = 8;
					Vec3 hit = new Vec3(tx, t.getY() + 0.5, tz);
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, t, false));
				}
				setKeys(false, false, false, false);
				return Signal.NONE;
			}
		}

		// Orientation — « pure pursuit » : vise entre le nœud courant et le suivant dans les virages.
		double aimX = tx, aimZ = tz;
		if (mv.type == PathEngine.WALK && !mv.door && !mv.waterRun && idx + 1 < path.nodes.size()) {
			PathEngine.Move mv2 = path.moves.get(idx);
			if (mv2.type == PathEngine.WALK && !mv2.door) {
				BlockPos n2 = path.nodes.get(idx + 1);
				double nx2 = n2.getX() + 0.5, nz2 = n2.getZ() + 0.5;
				double d2x = nx2 - feet.x, d2z = nz2 - feet.z;
				double l2 = Math.hypot(d2x, d2z);
				if (dist2 > 1e-3 && l2 > 1e-3) {
					double dot = (dx * d2x + dz * d2z) / (dist2 * l2);
					if (dot > 0.55 && dist2 < Math.max(1.6, v * 2.6)) {
						aimX = tx + (nx2 - tx) * 0.45;
						aimZ = tz + (nz2 - tz) * 0.45;
					}
				}
			}
		}
		float desiredYaw = (float) Math.toDegrees(Math.atan2(-(aimX - feet.x), aimZ - feet.z));
		boolean verticalAim = tk == WorldModel.WATER || tk == WorldModel.LAVA || tk == WorldModel.CLIMB || tk == WorldModel.FLY || mv.type == PathEngine.CLIMB_UP;
		float desiredPitch = verticalAim
				? Mth.clamp((float) -Math.toDegrees(Math.atan2(dy + (tk == WorldModel.CLIMB ? 0.4 : 0), Math.max(0.3, dist2))), -80, 80)
				: (mv.type == PathEngine.CLIMB_DOWN ? 70f : p.getXRot());
		aim(p, desiredYaw, desiredPitch, true, v);
		float err = Math.abs(Mth.wrapDegrees(desiredYaw - p.getYRot()));
		boolean aligned = err < (mv.type == PathEngine.PARKOUR ? 16 : 50);

		boolean forward = aligned, jump = false, sneak = false, sprint = false;

		if (mv.waterRun) {
			// Course sur l'eau : sprint permanent, aucun sneak, saut seulement si on s'enfonce.
			sprint = aligned;
			forward = aligned || (v > 0.2 && err < 75); // garde l'élan pendant le réalignement
			jump = p.isUnderWater() || feet.y < tf - 0.30;
			if (inWater && v < 0.12) jump = true; // remonter à la surface avant d'accélérer
		} else switch (mv.type) {
			case PathEngine.PARKOUR -> {
				sprint = aligned && cfg.sprint;
				forward = aligned;
				// Décollage prédictif : saute dès qu'un tick de course atteindrait le point idéal.
				if (onGround && aligned && p.isSprinting()
						&& dist2 <= mv.gap + 0.6 + v * 1.2
						&& dist2 >= mv.gap - 0.6 - v * 0.5) {
					jump = true;
				}
				if (!onGround) { forward = true; sprint = cfg.sprint; } // garde l'élan en l'air
			}
			case PathEngine.ASCEND -> {
				sprint = aligned && cfg.sprint && mv.sprint;
				if (onGround && aligned && dist2 < 1.2 + v && dist2 > 0.2) jump = true;
			}
			case PathEngine.DESCEND -> {
				// Garde santé live avant une chute risquée
				if (mv.drop > 0) {
					double dmg = model.phys.fallDamage(mv.drop) * model.landingMult(t.getX(), t.getY(), t.getZ());
					double budget = p.getHealth() + p.getAbsorptionAmount() - 2.0 * cfg.keepHearts;
					if (dmg > 0 && dmg >= budget) { release(); return Signal.FALL_UNSAFE; }
				}
				sprint = aligned && cfg.sprint;
				forward = aligned;
				if (!onGround) {
					// Contrôle en l'air + anti-dépassement : coupe l'avance si trop proche horizontalement.
					forward = dist2 >= Math.max(0.5, v * 1.4) && aligned;
				}
			}
			case PathEngine.CLIMB_UP -> {
				forward = aligned;
				if (model.scaff(t.getX(), t.getY(), t.getZ()) || model.scaff(p.getBlockX(), p.getBlockY(), p.getBlockZ())) jump = true;
			}
			case PathEngine.CLIMB_DOWN -> { forward = aligned; sneak = false; } // glisse le long de l'échelle
			case PathEngine.SCAFF_DOWN -> { forward = false; sneak = true; }
			case PathEngine.SWIM -> {
				forward = aligned || dist2 < 0.4;
				if (dy > 0.25 || (inWater && !p.isUnderWater() && dy > -0.2)) jump = true;
				sprint = cfg.sprint && aligned && p.isUnderWater();
			}
			case PathEngine.FLY -> {
				forward = aligned;
				if (dy > 0.3) jump = true;
				if (dy < -0.3) sneak = true;
				sprint = cfg.sprint && dist3 > 3;
			}
			default -> { // WALK (auto-step Skyblock assisté d'un saut client si besoin)
				forward = aligned;
				sprint = aligned && cfg.sprint && mv.sprint && dist2 > 1.6;
				if (dy > 0.55 && onGround && aligned && dist2 < 1.2 + v) jump = true;
			}
		}

		// Sécurité magma : accroupi (Swift Sneak accélère) — inutile si Résistance au feu / Frost Walker.
		if (cfg.sneakMagma && !model.magmaSafe() && (model.magma(p.getBlockX(), p.getBlockY() - 1, p.getBlockZ())
				|| model.magma(t.getX(), t.getY() - 1, t.getZ()))) { sneak = true; sprint = false; }
		if (inWater && !mv.waterRun && mv.type != PathEngine.SWIM && tk == WorldModel.GROUND) jump = jump || aligned;

		setKeys(forward, jump, sneak, sprint);

		// Anti-blocage
		double d = tk == WorldModel.GROUND || tk == WorldModel.SURFACE ? dist2 : dist3;
		if (d < bestDist - 0.04) { bestDist = d; stuckTicks = 0; } else stuckTicks++;
		if (p.horizontalCollision && forward) collisionTicks++; else collisionTicks = 0;
		stuckByCollision = collisionTicks > 20;
		if (stuckTicks > 60 || collisionTicks > 35) { release(); return Signal.STUCK; }
		return Signal.NONE;
	}

	private void aim(LocalPlayer p, double tx, double ty, double tz, boolean smooth) {
		float yaw = (float) Math.toDegrees(Math.atan2(-(tx - p.getX()), tz - p.getZ()));
		float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(ty - (p.getY() + p.getEyeHeight()), Math.max(0.3, Math.hypot(tx - p.getX(), tz - p.getZ())))), -80, 80);
		aim(p, yaw, pitch, smooth, speed(p));
	}

	private void aim(LocalPlayer p, float yaw, float pitch, boolean smooth, double v) {
		// Rotation lissee a chaque IMAGE (voir AimController / AmberHud), pas seulement a chaque tick.
		aim.setTarget(yaw, pitch, true, v, 1.0);
		aim.tickFallback(p);
	}

	private void setKeys(boolean forward, boolean jump, boolean sneak, boolean sprint) {
		var o = mc.options;
		o.keyUp.setDown(forward);
		o.keyDown.setDown(false); o.keyLeft.setDown(false); o.keyRight.setDown(false);
		o.keyJump.setDown(jump);
		o.keyShift.setDown(sneak);
		o.keySprint.setDown(sprint);
	}

	public double remainingDistance() {
		if (mc.player == null || idx >= path.nodes.size()) return 0;
		double d = mc.player.position().distanceTo(Vec3.atCenterOf(path.nodes.get(idx)));
		for (int i = idx; i < path.nodes.size() - 1; i++) d += Vec3.atCenterOf(path.nodes.get(i)).distanceTo(Vec3.atCenterOf(path.nodes.get(i + 1)));
		return d;
	}

	public double remainingTicks() {
		double t = 0;
		for (int i = Math.max(0, idx - 1); i < path.moves.size(); i++) t += path.moves.get(i).cost;
		return t;
	}
}