package dev.amberpath.path;

import dev.amberpath.config.AmberConfig;
import dev.amberpath.skyblock.SkyblockHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Orchestrateur : machine à états, recherche, exécution, replanification, gizmos, Skyblock.
 *
 * Pilotage UNIQUEMENT par /go (aucun menu, aucune touche). Dès que le joueur reprend
 * la main — inventaire/chat/autre écran, touches de déplacement, caméra à la souris —
 * la navigation est arrêtée : immédiatement au sol, ou une fois le bloc courant atteint
 * (atterrissage) si le joueur est en l'air, pour ne jamais le lâcher au-dessus du vide.
 * L'arrivée au bloc destination arrête évidemment aussi la navigation.
 */
public class Navigator {
	public enum Status { IDLE, COMPUTING, RUNNING, PAUSED, ARRIVED, FAILED }

	private final Minecraft mc = Minecraft.getInstance();
	public final AmberConfig cfg;
	public Status status = Status.IDLE;
	public PathEngine.Goal goal;
	public String pauseReason = "";
	public long arrivedAt = 0;
	public long failedAt = 0;
	public int lastExpansions = 0;
	public long lastComputeMs = 0;
	public double lastPathLen = 0;
	public double lastSpeedFactor = 1;
	public int lastMaxGap = 3;
	public int lastSmoothed = 0;

	private PathEngine engine;
	private PathExecutor executor;
	private PathEngine.PathResult path;
	private PathEngine.PathResult queuedPath;
	private WorldModel model;
	private final Map<Long, Long> tempBlocked = new HashMap<>();
	private float lastHealth = 20;
	private int damageWindow = 0;
	private int replans = 0;
	private int refreshTimer = 0;
	private int sbTimer = 0;
	private long retryAt = 0;
	private int activationFails = 0;

	// Arrêt différé : interférence manuelle détectée en l'air → on finit le bloc courant.
	private String pendingStop = null;
	private int stopGrace = 0;
	// Grâce post-/go : l'écran de chat peut encore être ouvert l'instant de l'exécution.
	private int screenGrace = 0;
	// Référence de caméra pendant les phases sans exécuteur (détection souris manuelle).
	private float snapYaw, snapPitch;
	private boolean snapValid = false;

	public Navigator(AmberConfig cfg) { this.cfg = cfg; }

	public boolean active() { return status == Status.COMPUTING || status == Status.RUNNING || status == Status.PAUSED; }
	public PlayerPhysics physics() { return model != null ? model.phys : null; }
	/** Appele a chaque image (HUD) : rotation camera fluide entre deux ticks. */
	public void frameAim() { if (status == Status.RUNNING && executor != null) executor.aim.frame(mc.player); }
	public boolean onWaterRun() { return executor != null && executor.currentWaterRun(); }

	public void msg(String text) {
		if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§6[AmberPath] §r" + text));
	}

	public void start(PathEngine.Goal g) {
		if (mc.player == null || mc.level == null) return;
		// La politique de navigation est fixe : aucun ancien fichier de config
		// ne doit pouvoir remettre un mode lent ou une recherche approximative.
		cfg.enforceNavigationPolicy();
		this.goal = g;
		this.replans = 0;
		this.retryAt = 0;
		this.pendingStop = null;
		this.activationFails = 0;
		this.lastHealth = mc.player.getHealth();
		if (executor != null) executor.release(); // relance /go : ne pas laisser nos touches simulées enfoncées
		this.engine = null;
		this.queuedPath = null;
		this.executor = null;
		this.status = Status.COMPUTING;
		this.screenGrace = 3;
		snapshotRot();
		mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
		msg("Navigation vers §e" + g.x + ", " + (g.y == null ? "auto" : g.y) + ", " + g.z + "§r… (§7/go stop§r pour arrêter)");
	}

	public void stop() {
		if (executor != null) executor.release();
		status = Status.IDLE;
		engine = null; executor = null; path = null; queuedPath = null;
		pendingStop = null;
		snapValid = false;
	}

	/**
	 * Reprise de main du joueur : arrêt demandé. Au sol (ou dans l'eau) → immédiat ;
	 * en l'air → on laisse le mouvement courant se terminer sur son bloc (atterrissage),
	 * avec un garde-fou de 3 s si le monde est en pause (inventaire en solo).
	 */
	private void requestStop(String reason) {
		if (pendingStop != null || !active()) return;
		LocalPlayer p = mc.player;
		if (p == null || executor == null || p.onGround() || p.isInWater()) {
			stop();
			msg("Navigation §carrêtée§r — " + reason + ".");
			return;
		}
		pendingStop = reason;
		stopGrace = 60;
	}

	private void snapshotRot() {
		LocalPlayer p = mc.player;
		if (p != null) { snapYaw = p.getYRot(); snapPitch = p.getXRot(); snapValid = true; }
	}

	private boolean manualKeys() {
		var o = mc.options;
		return o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()
				|| o.keyJump.isDown() || o.keyShift.isDown() || o.keySprint.isDown();
	}

	private boolean manualLook() {
		LocalPlayer p = mc.player;
		if (p == null) return false;
		if (executor != null) return executor.aim.externalLook(p);
		if (!snapValid) return false;
		return Math.abs(Mth.wrapDegrees(p.getYRot() - snapYaw)) > 1.5f || Math.abs(p.getXRot() - snapPitch) > 1.5f;
	}

	private BlockPos findStart() {
		LocalPlayer p = mc.player;
		int x = Mth_floor(p.getX()), z = Mth_floor(p.getZ());
		int y0 = Mth_floor(p.getY());
		for (int y = y0 + 2; y >= y0 - 3; y--) {
			if (model.kind(x, y, z) != WorldModel.INVALID) return new BlockPos(x, y, z);
		}
		if (p.onGround() || p.isInWater()) return new BlockPos(x, y0, z);
		return null; // en l'air au-dessus du vide : on attend d'atterrir
	}
	private static int Mth_floor(double v) { return (int) Math.floor(v); }

	private boolean beginSearch() {
		model = new WorldModel(mc.level, mc.player, cfg, tempBlocked.keySet());
		lastSpeedFactor = model.phys.effSpeed;
		lastMaxGap = cfg.adaptiveParkour ? Math.max(cfg.maxGap, model.phys.maxGapFlat) : cfg.maxGap;
		BlockPos s = findStart();
		if (s == null) { engine = null; return false; }
		engine = new PathEngine(model, cfg, s, goal);
		return true;
	}

	/** Pré-calcule le segment suivant depuis la frontière du segment actif. */
	private boolean beginLookahead(BlockPos from) {
		if (from == null || model == null || goal == null) return false;
		engine = new PathEngine(model, cfg, from, goal);
		queuedPath = null;
		return true;
	}

	private boolean usable(PathEngine.PathResult candidate) {
		return candidate != null && candidate.nodes != null && candidate.nodes.size() >= 2;
	}

	/** Active un segment après re-validation de ses premiers pas dans le monde live. */
	private boolean activatePath(PathEngine.PathResult candidate) {
		if (!usable(candidate)) return false;
		int lim = Math.min(candidate.nodes.size(), 5);
		for (int i = 1; i < lim; i++) {
			BlockPos n = candidate.nodes.get(i);
			if (model.kind(n.getX(), n.getY(), n.getZ()) == WorldModel.INVALID) return false;
		}
		path = candidate;
		lastPathLen = path.cost;
		lastSmoothed = path.smoothed;
		executor = new PathExecutor(mc, cfg, model, path);
		status = Status.RUNNING;
		activationFails = 0;
		if (path.partial) beginLookahead(path.nodes.get(path.nodes.size() - 1));
		return true;
	}

	private boolean goalReached() {
		LocalPlayer p = mc.player;
		if (goal == null) return false;
		Vec3 f = p.position();
		switch (goal.mode) {
			case 0:
				double dx0 = f.x - (goal.x + 0.5), dz0 = f.z - (goal.z + 0.5);
				return dx0 * dx0 + dz0 * dz0 < 0.5625 && (p.onGround() || p.isInWater())
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
		cfg.enforceNavigationPolicy();
		long now = System.currentTimeMillis();
		tempBlocked.entrySet().removeIf(e -> e.getValue() < now);
		if (cfg.skyblockAuto && ++sbTimer >= 20) { sbTimer = 0; SkyblockHelper.refresh(mc, cfg); }

		if (status == Status.IDLE || status == Status.ARRIVED || status == Status.FAILED) {
			// Réessai auto si l'objectif était hors chunks chargés
			if (status == Status.FAILED && retryAt > 0 && now >= retryAt && mc.player != null && mc.level != null) {
				retryAt = 0; engine = null; status = Status.COMPUTING;
			}
			return;
		}
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) { stop(); return; }
		if (p.isDeadOrDying()) { stop(); msg("§cJoueur mort — navigation arrêtée.§r"); return; }

		// --- Reprise de main manuelle → arrêt (immédiat au sol, sinon fin du bloc courant) ---
		if (screenGrace > 0) screenGrace--;
		if (mc.gui.screen() != null && screenGrace == 0) requestStop("interface ouverte");
		else if (executor != null ? executor.manualInput() : manualKeys()) requestStop("déplacement manuel");
		else if (manualLook()) requestStop("caméra déplacée à la main");

		if (pendingStop != null) {
			if (executor == null || p.onGround() || p.isInWater() || --stopGrace <= 0) {
				String reason = pendingStop;
				stop();
				msg("Navigation §carrêtée§r — " + reason + ".");
			} else {
				executor.tick(); // termine le saut / la chute en cours jusqu'au bloc
			}
			return;
		}

		// Pause auto (véhicule, lévitation, élytre, sommeil, dégâts si activé)
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
		if (vehiclePause || levPause || elytraPause || sleepPause || damagePause) {
			if (status != Status.PAUSED) {
				status = Status.PAUSED;
				pauseReason = vehiclePause ? "véhicule" : levPause ? "lévitation"
						: elytraPause ? "élytre" : sleepPause ? "sommeil" : "dégâts récents";
				if (executor != null) executor.release();
			}
			return;
		} else if (status == Status.PAUSED) {
			status = executor != null ? Status.RUNNING : Status.COMPUTING;
		}

		if (goalReached()) { arrive(); return; }

		if (status == Status.COMPUTING) {
			if (engine == null && !beginSearch()) { snapshotRot(); return; }
			engine.tick();
			if (engine.state == PathEngine.State.SEARCHING) { snapshotRot(); return; }
			lastExpansions = engine.expansions;
			lastComputeMs = engine.result() != null ? engine.result().timeMs : 0;
			PathEngine.PathResult computed = engine.result();
			engine = null;
			if (!activatePath(computed)) {
				if (usable(computed) && activationFails < 3) {
					// Le monde a changé sous nos pieds : nouvelle recherche immédiate.
					activationFails++;
					queuedPath = null;
					snapshotRot();
					return;
				}
				activationFails = 0;
				boolean goalLoaded = model.loaded(goal.x, goal.y == null ? Mth_floor(p.getY()) : goal.y, goal.z);
				if (!goalLoaded && cfg.autoReplan) {
					status = Status.FAILED; failedAt = now; retryAt = now + 1500;
					msg("Objectif hors chunks chargés — §enouvelle tentative automatique§r…");
				} else {
					status = Status.FAILED; failedAt = now;
					msg("§cAucun chemin trouvé§r (objectif inaccessible ou hors chunks chargés). §7/go <x> [y] <z>§r pour réessayer.");
					p.playSound(SoundEvents.VILLAGER_NO, 0.6f, 1f);
				}
				return;
			}
			if (path.partial && replans == 0) msg("Navigation lancée — calcul du prochain segment en arrière-plan…");
			return;
		}

		if (status == Status.RUNNING && executor != null) {
			// Recherche en avance pendant que le joueur suit le segment courant.
			if (engine != null && queuedPath == null) {
				engine.tick();
				if (engine.state != PathEngine.State.SEARCHING) {
					lastExpansions = engine.expansions;
					lastComputeMs = engine.result() != null ? engine.result().timeMs : 0;
					queuedPath = engine.result();
					engine = null;
				}
			}
			// Rafraîchit le profil physique live (effets qui expirent, santé, enchantements, ✦ Speed)
			if (++refreshTimer >= 40) { refreshTimer = 0; model.refresh(p); }
			PathExecutor.Signal sig = executor.tick();
			switch (sig) {
				case PATH_END -> {
					if (goalReached()) arrive();
					else if (queuedPath != null && usable(queuedPath)) {
						PathEngine.PathResult next = queuedPath;
						queuedPath = null;
						if (!activatePath(next)) { executor = null; status = Status.COMPUTING; }
					}
					else if (engine != null) {
						// Le segment est terminé avant le calcul anticipé : on attend ce calcul sans immobiliser le moteur.
						executor = null;
						status = Status.COMPUTING;
					}
					else if (cfg.autoReplan && replans < 256) {
						replans++; executor = null; queuedPath = null;
						status = Status.COMPUTING;
					}
					else { status = Status.FAILED; failedAt = now; msg("§cImpossible d'atteindre l'objectif.§r"); }
				}
				case FALL_UNSAFE -> {
					if (!cfg.autoReplan || replans >= 256) { status = Status.FAILED; failedAt = now; msg("§cChute trop dangereuse — arrêt.§r"); executor.release(); return; }
					replans++;
					BlockPos t = executor.target();
					if (t != null) { tempBlocked.put(t.asLong(), now + 12000); if (model != null) model.invalidate(); }
					msg("Chute évitée (santé insuffisante) — §erecalcul§r…");
					engine = null; queuedPath = null; executor = null; beginSearch(); status = Status.COMPUTING;
				}
				case JUMP_FAILED -> {
					// Saut manqué détecté en vol : recalcul immédiat + cible blacklistée
					// (on ne retentera pas ce saut impossible).
					if (!cfg.autoReplan || replans >= 256) { status = Status.FAILED; failedAt = now; msg("§cSaut impossible — navigation arrêtée.§r"); executor.release(); return; }
					replans++;
					BlockPos t = executor.target();
					if (t != null) { tempBlocked.put(t.asLong(), now + 20000); if (model != null) model.invalidate(); }
					msg("Saut manqué — §erecalcul immédiat§r (cible écartée).");
					engine = null; queuedPath = null; executor = null; beginSearch(); status = Status.COMPUTING;
				}
				case STUCK, BLOCKED -> {
					if (!cfg.autoReplan || replans >= 256) { status = Status.FAILED; failedAt = now; msg("§cBlocage — navigation arrêtée.§r"); executor.release(); return; }
					replans++;
					BlockPos t = executor.target();
					// Ne bloque le nœud que si c'est un vrai mur (pas un simple dépassement après chute).
					if (t != null && (sig == PathExecutor.Signal.BLOCKED || executor.stuckByCollision)) {
						tempBlocked.put(t.asLong(), now + 15000);
						if (model != null) model.invalidate();
					}
					engine = null; queuedPath = null; executor = null; beginSearch(); status = Status.COMPUTING;
				}
				default -> {}
			}
		}

		// Référence de caméra fraîche pour toute phase sans exécuteur (détection souris manuelle).
		if (status == Status.COMPUTING && executor == null) snapshotRot();
	}

	private void arrive() {
		if (executor != null) executor.release();
		status = Status.ARRIVED;
		arrivedAt = System.currentTimeMillis();
		executor = null; path = null; engine = null; queuedPath = null;
		LocalPlayer p = mc.player;
		if (p != null) {
			p.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.9f, 1.0f);
			p.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
		}
		msg("§aArrivé à destination ✓§r  (§e" + goal.x + ", " + (goal.y == null ? "auto" : goal.y) + ", " + goal.z + "§r) — navigation terminée.");
	}

	public void tickEnd() {
		if (mc.level == null || mc.player == null) return;
		if (!cfg.showPath || path == null || status != Status.RUNNING || executor == null) return;
		try (Gizmos.TemporaryCollection c = mc.collectPerTickGizmos()) {
			Vec3 prev = mc.player.position().add(0, 0.1, 0);
			for (int i = executor.idx; i < path.nodes.size(); i++) {
				BlockPos n = path.nodes.get(i);
				Vec3 cur = Vec3.atCenterOf(n).add(0, -0.4, 0);
				boolean water = i - 1 >= 0 && i - 1 < path.moves.size() && path.moves.get(i - 1).waterRun;
				Gizmos.line(prev, cur, water ? 0xCC7FD4E8 : 0xCCE8A85C, 2.0f).persistForMillis(120);
				prev = cur;
			}
			if (goal != null) {
				int gy = goal.y == null ? findColumnTop(goal.x, goal.z) : goal.y;
				Gizmos.cuboid(new BlockPos(goal.x, gy, goal.z), GizmoStyle.stroke(0xFFE8A85C, 2.0f)).persistForMillis(120).setAlwaysOnTop();
				Gizmos.point(Vec3.atCenterOf(new BlockPos(goal.x, gy + 1, goal.z)), 0xFFE8A85C, 6f).persistForMillis(120).setAlwaysOnTop();
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
