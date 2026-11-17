package dev.amberpath.path;

import dev.amberpath.config.AmberConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Suit le chemin calculé en simulant les entrées clavier vanilla (anti-détection friendly). */
public class PathExecutor {
	public enum Signal { NONE, PATH_END, STUCK, BLOCKED }

	private final Minecraft mc;
	private final AmberConfig cfg;
	private final WorldModel model;
	private final PathEngine.PathResult path;
	public int idx = 1;
	private int stuckTicks = 0, collisionTicks = 0, doorCooldown = 0, validateTimer = 0;
	private double bestDist = Double.MAX_VALUE;
	private int ticks = 0;

	public PathExecutor(Minecraft mc, AmberConfig cfg, WorldModel model, PathEngine.PathResult path) {
		this.mc = mc; this.cfg = cfg; this.model = model; this.path = path;
	}

	public BlockPos target() { return idx < path.nodes.size() ? path.nodes.get(idx) : null; }

	public void release() {
		var o = mc.options;
		o.keyUp.setDown(false); o.keyDown.setDown(false); o.keyLeft.setDown(false);
		o.keyRight.setDown(false); o.keyJump.setDown(false); o.keyShift.setDown(false); o.keySprint.setDown(false);
	}

	public Signal tick() {
		LocalPlayer p = mc.player;
		if (p == null) return Signal.NONE;
		ticks++;
		if (idx >= path.nodes.size()) { release(); return Signal.PATH_END; }

		// Validation périodique du chemin (blocs posés/cassés, portes fermées…)
		if (++validateTimer >= 10) {
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
		double tf = model.floorOf(tk, t.getX(), t.getY(), t.getZ());
		if (Double.isNaN(tf)) tf = t.getY();

		Vec3 feet = p.position();
		double tx = t.getX() + 0.5, tz = t.getZ() + 0.5;
		double dx = tx - feet.x, dz = tz - feet.z;
		double dist2 = Math.sqrt(dx * dx + dz * dz);
		double dy = tf - feet.y;
		double dist3 = Math.sqrt(dx * dx + dy * dy + dz * dz);

		// Avancement
		boolean advanced = (tk == WorldModel.GROUND)
				? dist2 < 0.42 && Math.abs(feet.y - tf) < 0.75
				: dist3 < 0.65;
		if (advanced) { idx++; stuckTicks = 0; bestDist = Double.MAX_VALUE; return Signal.NONE; }

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

		// Orientation
		float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		boolean verticalAim = tk == WorldModel.WATER || tk == WorldModel.LAVA || tk == WorldModel.CLIMB || tk == WorldModel.FLY || mv.type == PathEngine.CLIMB_UP;
		float desiredPitch = verticalAim ? Mth.clamp((float) -Math.toDegrees(Math.atan2(dy + (tk == WorldModel.CLIMB ? 0.4 : 0), Math.max(0.3, dist2))), -80, 80) : p.getXRot();
		aim(p, desiredYaw, desiredPitch, cfg.smoothRotation);
		float err = Math.abs(Mth.wrapDegrees(desiredYaw - p.getYRot()));
		boolean aligned = err < (mv.type == PathEngine.PARKOUR ? 14 : 38);

		boolean forward = aligned, jump = false, sneak = false, sprint = false;
		boolean inWater = p.isInWater();
		boolean onGround = p.onGround();

		switch (mv.type) {
			case PathEngine.PARKOUR -> {
				sprint = aligned && cfg.sprint;
				forward = aligned;
				if (onGround && aligned && p.isSprinting() && dist2 <= mv.gap + 0.6 && dist2 >= mv.gap - 0.45) jump = true;
				if (!onGround) forward = true; // garder l'élan en l'air
			}
			case PathEngine.ASCEND -> {
				sprint = aligned && cfg.sprint && mv.sprint;
				if (onGround && aligned && dist2 < 1.2 && dist2 > 0.3) jump = true;
			}
			case PathEngine.DESCEND -> {
				forward = aligned;
				if (dist2 < 0.5 && onGround) forward = true;
			}
			case PathEngine.CLIMB_UP -> {
				forward = aligned;
				if (model.scaff(t.getX(), t.getY(), t.getZ()) || model.scaff(p.blockPosition().getX(), p.blockPosition().getY(), p.blockPosition().getZ())) jump = true;
			}
			case PathEngine.CLIMB_DOWN -> {
				forward = false; sneak = false; // glisse le long de l'échelle
			}
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
			default -> { // WALK
				forward = aligned;
				sprint = aligned && cfg.sprint && mv.sprint && dist2 > 1.6;
				if (dy > 0.55 && onGround && aligned && dist2 < 1.2) jump = true; // petite marche non auto-step
			}
		}

		// Sécurité magma : sneak
		if (cfg.sneakMagma && (model.magma(p.blockPosition().getX(), p.blockPosition().getY() - 1, p.blockPosition().getZ())
				|| model.magma(t.getX(), t.getY() - 1, t.getZ()))) { sneak = true; sprint = false; }
		if (inWater && mv.type != PathEngine.SWIM && tk == WorldModel.GROUND) jump = jump || aligned;

		setKeys(forward, jump, sneak, sprint);

		// Anti-blocage
		double d = tk == WorldModel.GROUND ? dist2 : dist3;
		if (d < bestDist - 0.04) { bestDist = d; stuckTicks = 0; } else stuckTicks++;
		if (p.horizontalCollision && forward) collisionTicks++; else collisionTicks = 0;
		if (stuckTicks > 55 || collisionTicks > 35) { release(); return Signal.STUCK; }
		return Signal.NONE;
	}

	private void aim(LocalPlayer p, double tx, double ty, double tz, boolean smooth) {
		float yaw = (float) Math.toDegrees(Math.atan2(-(tx - p.getX()), tz - p.getZ()));
		float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(ty - (p.getY() + p.getEyeHeight()), Math.max(0.3, Math.hypot(tx - p.getX(), tz - p.getZ())))), -80, 80);
		aim(p, yaw, pitch, smooth);
	}

	private void aim(LocalPlayer p, float yaw, float pitch, boolean smooth) {
		float step = smooth ? 32f : 180f;
		float ny = p.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - p.getYRot()), -step, step);
		float np = p.getXRot() + Mth.clamp(pitch - p.getXRot(), -step, step);
		p.setYRot(ny); p.setXRot(Mth.clamp(np, -89, 89));
		p.setYHeadRot(ny); p.yBodyRot = ny;
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
