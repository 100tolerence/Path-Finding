package dev.amberpath.path;

import dev.amberpath.AmberPathClient;
import dev.amberpath.config.AmberConfig;
import dev.amberpath.gui.AmberPathScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** Orchestrateur : machine à états, recherche, exécution, replanification, gizmos. */
public class Navigator {
	public enum Status { IDLE, COMPUTING, RUNNING, PAUSED, ARRIVED, FAILED }

	private final Minecraft mc = Minecraft.getInstance();
	public final AmberConfig cfg;
	public Status status = Status.IDLE;
	public PathEngine.Goal goal;
	public String pauseReason = "";
	public long arrivedAt = 0;
	public int lastExpansions = 0;
	public long lastComputeMs = 0;
	public double lastPathLen = 0;
	public double lastSpeedFactor = 1;
	public int lastMaxGap = 3;

	private PathEngine engine;
	private PathExecutor executor;
	private PathEngine.PathResult path;
	private WorldModel model;
	private final Map<Long, Long> tempBlocked = new HashMap<>();
	private float lastHealth = 20;
	private int damageWindow = 0;
	private int replans = 0;
	private int refreshTimer = 0;
	private long retryAt = 0;

	public Navigator(AmberConfig cfg) { this.cfg = cfg; }

	public boolean active() { return status == Status.COMPUTING || status == Status.RUNNING || status == Status.PAUSED; }
	public PlayerPhysics physics() { return model != null ? model.phys : null; }

	public void msg(String text) {
		if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§6[AmberPath] §r" + text));
	}

	public void start(PathEngine.Goal g) {
		if (mc.player == null || mc.level == null) return;
		this.goal = g;
		this.replans = 0;
		this.retryAt = 0;
		this.lastHealth = mc.player.getHealth();
		beginSearch();
		status = Status.COMPUTING;
		mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
		msg("Navigation vers §e" + g.x + ", " + (g.y == null ? "auto" : g.y) + ", " + g.z + "§r…");
	}

	public void stop() {
		if (executor != null) executor.release();
		status = Status.IDLE;
		engine = null; executor = null; path = null;
	}

	public void toggle() {
		if (active()) {
			stop(); msg("Navigation §carrêtée§r.");
			if (mc.player != null) mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 0.8f);
		} else if (goal != null) start(goal);
		else { mc.setScreenAndShow(new AmberPathScreen()); msg("Définissez d'abord une destination (§eG§r par défaut)."); }
	}

	private BlockPos findStart() {
		LocalPlayer p = mc.player;
		int x = Mth_floor(p.getX()), z = Mth_floor(p.getZ());
		int y0 = Mth_floor(p.getY());
		for (int y = y0 + 1; y >= y0 - 2; y--) {
			if (model.kind(x, y, z) != WorldModel.INVALID) return new BlockPos(x, y, z);
		}
		return new BlockPos(x, y0, z);
	}
	private static int Mth_floor(double v) { return (int) Math.floor(v); }

	private void beginSearch() {
		model = new WorldModel(mc.level, mc.player, cfg, tempBlocked.keySet());
		lastSpeedFactor = model.phys.speedFactor;
		lastMaxGap = cfg.adaptiveParkour ? Math.max(cfg.maxGap, model.phys.maxGapFlat) : cfg.maxGap;
		engine = new PathEngine(model, cfg, findStart(), goal);
	}

	private boolean goalReached() {
		LocalPlayer p = mc.player;
		if (goal == null) return false;
		Vec3 f = p.position();
		switch (goal.mode) {
			case 0:
				return Mth_floor(f.x) == goal.x && Mth_floor(f.z) == goal.z && p.onGround()
						&& (goal.y == null || Math.abs(f.y - (goal.y + 1)) < 0.8);
			case 1:
				return Mth_floor(f.x) == goal.x && Mth_floor(f.z) == goal.z
						&& (goal.y == null || Math.abs(f.y - goal.y) < 0.8);
			default:
				double dx = f.x - (goal.x + 0.5), dz = f.z - (goal.z + 0.5);
				return dx * dx + dz * dz <= (goal.radius + 0.5) * (goal.radius + 0.5)
						&& (goal.y == null || Math.abs(f.y - goal.y) <= goal.radius + 0.5);
		}
	}

	public void tickStart() {
		long now = System.currentTimeMillis();
		tempBlocked.entrySet().removeIf(e -> e.getValue() < now);
		if (status == Status.IDLE || status == Status.ARRIVED || status == Status.FAILED) {
			// Réessai auto si l'objectif était hors chunks chargés
			if (status == Status.FAILED && retryAt > 0 && now >= retryAt && mc.player != null && mc.level != null) {
				retryAt = 0; beginSearch(); status = Status.COMPUTING;
			}
			return;
		}
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) { stop(); return; }
		if (p.isDeadOrDying()) { stop(); msg("§cJoueur mort — navigation arrêtée.§r"); return; }

		// Pause auto (menus, véhicule, lévitation, élytre, sommeil, dégâts si activé)
		boolean menuPause = cfg.pauseInMenus && mc.gui.screen() != null && !(mc.gui.screen() instanceof AmberPathScreen);
		boolean vehiclePause = p.getVehicle() != null;
		boolean levPause = p.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION);
		boolean elytraPause = p.isFallFlying();
		boolean sleepPause = p.isSleeping();
		if (cfg.pauseOnDamage) {
			if (p.getHealth() < lastHealth - 0.5f) damageWindow = 20;
			lastHealth = p.getHealth();
			if (damageWindow > 0) damageWindow--;
		}
		boolean damagePause = cfg.pauseOnDamage && damageWindow > 0 && p.getHealth() < p.getMaxHealth() - 1;
		if (menuPause || vehiclePause || levPause || elytraPause || sleepPause || damagePause) {
			if (status != Status.PAUSED) {
				status = Status.PAUSED;
				pauseReason = menuPause ? "menu ouvert" : vehiclePause ? "véhicule" : levPause ? "lévitation"
						: elytraPause ? "élytre" : sleepPause ? "sommeil" : "dégâts récents";
				if (executor != null) executor.release();
			}
			return;
		} else if (status == Status.PAUSED) {
			status = executor != null ? Status.RUNNING : Status.COMPUTING;
		}

		if (goalReached()) { arrive(); return; }

		if (status == Status.COMPUTING) {
			engine.tick();
			if (engine.state == PathEngine.State.SEARCHING) return;
			lastExpansions = engine.expansions;
			lastComputeMs = engine.result() != null ? engine.result().timeMs : 0;
			if (engine.state == PathEngine.State.FAILED || engine.result() == null || engine.result().nodes.size() < 2) {
				boolean goalLoaded = model.loaded(goal.x, goal.y == null ? Mth_floor(p.getY()) : goal.y, goal.z);
				if (!goalLoaded && cfg.autoReplan) {
					status = Status.FAILED; retryAt = now + 1500;
					msg("Objectif hors chunks chargés — §enouvelle tentative automatique§r…");
				} else {
					status = Status.FAILED;
					msg("§cAucun chemin trouvé§r (objectif inaccessible ou hors chunks chargés).");
					p.playSound(SoundEvents.VILLAGER_NO, 0.6f, 1f);
				}
				return;
			}
			path = engine.result();
			lastPathLen = path.cost;
			executor = new PathExecutor(mc, cfg, model, path);
			status = Status.RUNNING;
			if (path.partial) msg("Chemin partiel calculé (§e" + path.nodes.size() + "§r nœuds) — progression vers la frontière chargée…");
			return;
		}

		if (status == Status.RUNNING && executor != null) {
			// Rafraîchit le profil physique live (effets qui expirent, santé, enchantements)
			if (++refreshTimer >= 40) { refreshTimer = 0; model.refresh(p); }
			PathExecutor.Signal sig = executor.tick();
			switch (sig) {
				case PATH_END -> {
					if (goalReached()) arrive();
					else if (cfg.autoReplan && replans < 64) { replans++; beginSearch(); status = Status.COMPUTING; }
					else { status = Status.FAILED; msg("§cImpossible d'atteindre l'objectif.§r"); }
				}
				case FALL_UNSAFE -> {
					if (!cfg.autoReplan || replans >= 64) { status = Status.FAILED; msg("§cChute trop dangereuse — arrêt.§r"); executor.release(); return; }
					replans++;
					BlockPos t = executor.target();
					if (t != null) tempBlocked.put(t.asLong(), now + 12000);
					msg("Chute évitée (santé insuffisante) — §erecalcul§r…");
					beginSearch(); status = Status.COMPUTING;
				}
				case STUCK, BLOCKED -> {
					if (!cfg.autoReplan || replans >= 64) { status = Status.FAILED; msg("§cBlocage — navigation arrêtée.§r"); executor.release(); return; }
					replans++;
					BlockPos t = executor.target();
					// Ne bloque le nœud que si c'est un vrai mur (pas un simple dépassement après chute).
					if (t != null && (sig == PathExecutor.Signal.BLOCKED || executor.stuckByCollision))
						tempBlocked.put(t.asLong(), now + 15000);
					beginSearch(); status = Status.COMPUTING;
				}
				default -> {}
			}
		}
	}

	private void arrive() {
		if (executor != null) executor.release();
		status = Status.ARRIVED;
		arrivedAt = System.currentTimeMillis();
		executor = null; path = null;
		LocalPlayer p = mc.player;
		if (p != null) {
			p.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.9f, 1.0f);
			p.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
		}
		msg("§aArrivé à destination ✓§r  (§e" + goal.x + ", " + (goal.y == null ? "auto" : goal.y) + ", " + goal.z + "§r)");
	}

	public void tickEnd() {
		if (mc.level == null || mc.player == null) return;
		if (!cfg.showPath || path == null || status != Status.RUNNING || executor == null) return;
		try (Gizmos.TemporaryCollection c = mc.collectPerTickGizmos()) {
			Vec3 prev = mc.player.position().add(0, 0.1, 0);
			for (int i = executor.idx; i < path.nodes.size(); i++) {
				BlockPos n = path.nodes.get(i);
				Vec3 cur = Vec3.atCenterOf(n).add(0, -0.4, 0);
				Gizmos.line(prev, cur, 0xCCE8A85C, 2.0f).persistForMillis(120);
				prev = cur;
			}
			if (goal != null) {
				int gy = goal.y == null ? findColumnTop(goal.x, goal.z) : goal.y;
				Gizmos.cuboid(new BlockPos(goal.x, gy, goal.z), GizmoStyle.stroke(0xFFE8A85C, 2.0f)).persistForMillis(120).setAlwaysOnTop();
				Gizmos.point(Vec3.atCenterOf(new BlockPos(goal.x, gy + 1, goal.z)), 0xFFE8A85C, 6f).persistForMillis(120);
			}
		} catch (Throwable ignored) {}
	}

	private int findColumnTop(int x, int z) {
		int y = Mth_floor(mc.player.getY());
		for (int dy = 20; dy >= -20; dy--) {
			int yy = y + dy;
			if (model != null && model.kind(x, yy, z) == WorldModel.GROUND) return yy - 1;
		}
		return y;
	}

	public double remainingDistance() { return executor != null ? executor.remainingDistance() : 0; }
	public double remainingTicks() { return executor != null ? executor.remainingTicks() : 0; }
	public int pathNodes() { return path != null ? path.nodes.size() : 0; }
	public int progressIdx() { return executor != null ? executor.idx : 0; }
}
