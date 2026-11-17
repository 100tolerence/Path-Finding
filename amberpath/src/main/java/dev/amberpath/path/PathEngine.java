package dev.amberpath.path;

import dev.amberpath.config.AmberConfig;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* incrémental (budget temps par tick) sur grille de blocs. Les mouvements sont
 * dérivés de {@link PlayerPhysics} : la portée des sauts parkour grandit avec Speed
 * (même niveau 255), la montée avec Jump Boost (y compris sur clôtures / murs), les
 * chutes sont bornées par le budget santé (Feather Falling / Slow Falling / atterrissage
 * eau/foin/slime) et par la profondeur de scan — garde anti-void : aucune chute n'est
 * proposée sans atterrissage confirmé. Les mobs sont ignorés.
 *
 * Skyblock : auto-step (monter un bloc entier en marchant à haute ✦ Speed), course SUR
 * l'eau (nœuds SURFACE, sprint forcé, sans saut), Frost Walker. Si l'objectif est hors
 * chunks chargés, renvoie un chemin partiel vers la frontière (replanification auto).
 *
 * Le chemin final est lissé (« string-pulling ») : les longs segments droits deviennent
 * une seule consigne, ce qui réduit les zigzags et file à très haute vitesse.
 */
public class PathEngine {
	public enum State { SEARCHING, FOUND, PARTIAL, FAILED }

	public static final int WALK = 0, ASCEND = 1, DESCEND = 2, PARKOUR = 3, SWIM = 4,
			CLIMB_UP = 5, CLIMB_DOWN = 6, SCAFF_DOWN = 7, FLY = 8;

	public static class Move {
		public int type; public double cost; public int gap; public boolean sprint; public boolean door;
		public boolean waterRun; // course sur l'eau (Skyblock haute vitesse)
		public double drop; // hauteur de chute (DESCEND) pour garde santé live
		Move(int type, double cost) { this.type = type; this.cost = cost; }
	}

	public static class PathResult {
		public List<BlockPos> nodes = new ArrayList<>();
		public List<Move> moves = new ArrayList<>();
		public double cost; public boolean partial; public int expansions; public long timeMs; public int smoothed;
	}

	public static class Goal {
		public final int mode, x, z, radius; public final Integer y;
		public Goal(int mode, int x, Integer y, int z, int radius) {
			this.mode = mode; this.x = x; this.y = y; this.z = z; this.radius = radius;
		}
	}

	private static class GNode {
		int x, y, z, kind; double floor, g, h; GNode parent; Move move; boolean closed;
		long key() { return BlockPos.asLong(x, y, z); }
		double f(double w) { return g + w * h; }
	}

	private final WorldModel m;
	private final AmberConfig cfg;
	private final PlayerPhysics ph;
	private final Goal goal;
	private final Map<Long, GNode> all = new HashMap<>();
	private final PriorityQueue<GNode> open;
	private final GNode start;
	private GNode best;
	public State state = State.SEARCHING;
	public int expansions = 0;
	private final long startMs = System.currentTimeMillis();
	private PathResult result;
	private final double stepTol;

	private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

	public PathEngine(WorldModel m, AmberConfig cfg, BlockPos from, Goal goal) {
		this.m = m; this.cfg = cfg; this.ph = m.phys; this.goal = goal;
		this.stepTol = Math.max(0.6, ph.effectiveStep());
		this.open = new PriorityQueue<>((a, b) -> {
			int c = Double.compare(a.f(cfg.weight), b.f(cfg.weight));
			return c != 0 ? c : Double.compare(a.h, b.h);
		});
		int k = m.kind(from.getX(), from.getY(), from.getZ());
		if (k == WorldModel.INVALID) k = WorldModel.GROUND;
		start = new GNode();
		start.x = from.getX(); start.y = from.getY(); start.z = from.getZ();
		start.kind = k; start.floor = m.floorOf(k, start.x, start.y, start.z);
		if (Double.isNaN(start.floor)) start.floor = start.y;
		start.g = 0; start.h = heuristic(start);
		all.put(start.key(), start); open.add(start); best = start;
	}

	private double heuristic(GNode n) {
		double dx = n.x - goal.x, dz = n.z - goal.z;
		double d2 = Math.sqrt(dx * dx + dz * dz);
		if (goal.mode == 2) d2 = Math.max(0, d2 - goal.radius);
		double dy = goal.y == null ? 0 : Math.abs(n.y - goal.y);
		if (goal.mode == 2 && goal.y != null) dy = Math.max(0, dy - goal.radius);
		double base = (n.kind == WorldModel.FLY ? ph.flyCost : 2.6) / ph.effSpeed;
		return d2 * base + dy * base * 0.85;
	}

	private boolean isGoal(GNode n) {
		switch (goal.mode) {
			case 0:
				if (n.x != goal.x || n.z != goal.z || n.kind != WorldModel.GROUND) return false;
				return goal.y == null || Math.abs(n.floor - (goal.y + 1)) < 0.75;
			case 1:
				return n.x == goal.x && n.z == goal.z && (goal.y == null || n.y == goal.y);
			default:
				double dx = n.x - goal.x, dz = n.z - goal.z;
				if (dx * dx + dz * dz > (double) goal.radius * goal.radius) return false;
				return goal.y == null || Math.abs(n.y - goal.y) <= goal.radius;
		}
	}

	public void tick() {
		if (state != State.SEARCHING) return;
		long deadline = System.nanoTime() + cfg.budgetMs * 1_000_000L;
		int checks = 0;
		while (!open.isEmpty()) {
			if ((++checks & 63) == 0 && System.nanoTime() > deadline) return;
			GNode n = open.poll();
			if (n.closed || all.get(n.key()) != n) continue;
			n.closed = true;
			expansions++;
			if (isGoal(n)) { finish(n, false); return; }
			if (n.h < best.h) best = n;
			if (expansions >= cfg.maxNodes) { finish(best, best != start); return; }
			expand(n);
		}
		finish(best, best != start);
	}

	private void offer(GNode from, Move mv, int x, int y, int z, int kind, double floor) {
		long key = BlockPos.asLong(x, y, z);
		double ng = from.g + mv.cost;
		GNode ex = all.get(key);
		if (ex != null && (ex.closed || ex.g <= ng + 1e-6)) return;
		GNode n = new GNode();
		n.x = x; n.y = y; n.z = z; n.kind = kind; n.floor = floor;
		n.g = ng; n.parent = from; n.move = mv;
		n.h = heuristic(n);
		all.put(key, n); open.add(n);
	}

	private int gapFlat() { return cfg.adaptiveParkour ? Math.max(cfg.maxGap, ph.maxGapFlat) : cfg.maxGap; }
	private int gapDown() { return cfg.adaptiveParkour ? Math.max(cfg.maxGap + 1, ph.maxGapDown) : cfg.maxGap + 1; }

	private void expand(GNode n) {
		if (n.kind == WorldModel.FLY) { expandFly(n); return; }
		if (n.kind == WorldModel.SURFACE) { expandSurface(n); return; }
		boolean sprintOk = cfg.sprint && ph.canSprint;
		boolean honeyBelow = m.honey(n.x, n.y - 1, n.z);
		boolean iceBelow = m.ice(n.x, n.y, n.z);
		double f0 = n.floor;
		double walkBase = sprintOk ? ph.sprintCost : ph.walkCost;
		double budget = ph.health + ph.absorption - 2.0 * cfg.keepHearts;

		// --- Mouvements verticaux (escalade / nage) ---
		if (n.kind == WorldModel.CLIMB) {
			int ku = m.kind(n.x, n.y + 1, n.z);
			if (ku == WorldModel.CLIMB) offer(n, new Move(CLIMB_UP, 8), n.x, n.y + 1, n.z, ku, n.y + 1);
			else if (ku == WorldModel.GROUND) offer(n, new Move(CLIMB_UP, 11), n.x, n.y + 1, n.z, ku, m.floorOf(ku, n.x, n.y + 1, n.z));
			int kd = m.kind(n.x, n.y - 1, n.z);
			if (kd == WorldModel.CLIMB) {
				boolean sc = m.scaff(n.x, n.y, n.z);
				offer(n, new Move(sc ? SCAFF_DOWN : CLIMB_DOWN, sc ? 3.5 : 6.7), n.x, n.y - 1, n.z, kd, n.y - 1);
			}
		}
		if (n.kind == WorldModel.WATER || n.kind == WorldModel.LAVA) {
			double base = n.kind == WorldModel.LAVA ? ph.lavaCost : (m.water(n.x, n.y + 1, n.z) ? ph.swimCost : ph.swimCost * 0.8);
			int ku = m.kind(n.x, n.y + 1, n.z);
			if (ku == WorldModel.WATER || ku == WorldModel.LAVA) offer(n, new Move(SWIM, base + 1), n.x, n.y + 1, n.z, ku, n.y + 1);
			else if (ku == WorldModel.SURFACE) offer(n, new Move(SWIM, base * 0.8), n.x, n.y + 1, n.z, ku, n.y + 1 + 0.875);
			else if (ku == WorldModel.GROUND && m.floorOf(ku, n.x, n.y + 1, n.z) <= n.y + 1.2)
				offer(n, new Move(ASCEND, 9), n.x, n.y + 1, n.z, ku, m.floorOf(ku, n.x, n.y + 1, n.z));
			int kd = m.kind(n.x, n.y - 1, n.z);
			if (kd == WorldModel.WATER || kd == WorldModel.LAVA) offer(n, new Move(SWIM, base), n.x, n.y - 1, n.z, kd, n.y - 1);
		}

		int maxDrop = (int) Math.min(cfg.maxFallScan, m.maxDrop());
		int clearanceTop = Math.min(8, (int) Math.ceil(ph.jumpPeak) + 1);

		for (int[] d : DIRS) {
			int dx = d[0], dz = d[1];
			boolean diag = dx != 0 && dz != 0;
			if (diag && !cfg.diagonals) continue;
			int nx = n.x + dx, nz = n.z + dz;
			if (diag && (!m.passable(nx, n.y, n.z) || !m.passable(nx, n.y + 1, n.z)
					|| !m.passable(n.x, n.y, nz) || !m.passable(n.x, n.y + 1, nz))) continue;
			double dirF = diag ? 1.4142 : 1.0;

			// --- Nage horizontale ---
			if (n.kind == WorldModel.WATER || n.kind == WorldModel.LAVA) {
				double base = n.kind == WorldModel.LAVA ? ph.lavaCost : (m.water(n.x, n.y + 1, n.z) ? ph.swimCost : ph.swimCost * 0.8);
				for (int ty = n.y + 1; ty >= n.y - 1; ty--) {
					int k = m.kind(nx, ty, nz);
					if (k == WorldModel.WATER || k == WorldModel.LAVA)
						offer(n, new Move(SWIM, base * dirF + m.cellExtra(nx, ty, nz)), nx, ty, nz, k, ty);
					else if (k == WorldModel.SURFACE && ty >= n.y)
						offer(n, new Move(SWIM, base * dirF * 0.8), nx, ty, nz, k, ty + 0.875);
					else if (k == WorldModel.GROUND && ty >= n.y && m.floorOf(k, nx, ty, nz) - n.y <= 1.2)
						offer(n, new Move(ASCEND, 9 * dirF), nx, ty, nz, k, m.floorOf(k, nx, ty, nz));
				}
				continue;
			}

			// --- Marche / montée / chute vers la colonne voisine ---
			boolean nextWalk = false;
			for (int ty = n.y + 1; ty >= n.y - maxDrop; ty--) {
				if (ty < n.y && !m.passable(nx, ty + 1, nz)) break; // gaine de chute obstruée
				int k = m.kind(nx, ty, nz);
				if (k == WorldModel.INVALID) continue;
				double f1 = m.floorOf(k, nx, ty, nz);

				// Course sur l'eau (Skyblock) : entrée depuis la terre ferme.
				if (k == WorldModel.SURFACE) {
					if (ty == n.y) {
						double df = f1 - f0;
						if (df <= stepTol && df >= -0.6) {
							Move mv = new Move(WALK, ph.waterRunCost * dirF);
							mv.sprint = true; mv.waterRun = true;
							offer(n, mv, nx, ty, nz, k, f1);
							nextWalk = true;
						}
					} else if (ty == n.y - 1 && cfg.descend) {
						double drop = f0 - f1;
						if (drop <= 2.6) { // petite chute SUR l'eau, aucun dégât
							Move mv = new Move(DESCEND, m.fallTime(drop) + 1);
							mv.drop = 0; mv.sprint = true; mv.waterRun = true;
							offer(n, mv, nx, ty, nz, k, f1);
						}
					}
					continue;
				}
				if (k == WorldModel.WATER || k == WorldModel.LAVA) {
					if (ty <= n.y && cfg.swim) {
						double drop = Math.max(0, f0 - ty);
						offer(n, new Move(SWIM, m.fallTime(drop) + 3), nx, ty, nz, k, ty);
					}
					continue;
				}
				if (k == WorldModel.CLIMB) {
					if (ty == n.y) offer(n, new Move(WALK, 5 * dirF + 2), nx, ty, nz, k, ty);
					continue;
				}
				// GROUND
				double df = f1 - f0;
				if (ty == n.y && Math.abs(df) <= stepTol) {
					double slow = m.surfaceFactor(nx, ty, nz) * m.surfaceFactor(n.x, n.y, n.z);
					double cost = walkBase * dirF * Math.max(1, slow) + m.cellExtra(nx, ty, nz);
					Move mv = new Move(WALK, cost);
					mv.sprint = sprintOk; mv.door = m.doorClosed(nx, ty, nz);
					offer(n, mv, nx, ty, nz, k, f1);
					nextWalk = true;
				} else if (df > stepTol && df <= ph.jumpPeak - 0.08 && ty == n.y + 1 && !honeyBelow && m.passable(n.x, n.y + 2, n.z)) {
					// Montée par saut — hauteur adaptée à Jump Boost (clôtures 1.5, murs…).
					Move mv = new Move(ASCEND, sprintOk ? 10 : 11);
					mv.sprint = sprintOk;
					offer(n, mv, nx, ty, nz, k, f1);
				} else if (df < -stepTol && cfg.descend) {
					double drop = -df;
					double mult = m.landingMult(nx, ty, nz);
					double dmg = ph.fallDamage(drop) * mult;
					boolean safe = mult == 0;
					if (!safe && !cfg.riskyFalls && drop > cfg.maxFall) continue;
					if (dmg > 0 && dmg >= budget) continue; // ne jamais mourir
					double penalty = dmg > 0 ? dmg * 8 : (drop > 3.5 ? 6 * (drop - 3) : 0);
					Move mv = new Move(DESCEND, m.fallTime(drop) + 3 + penalty);
					mv.drop = drop;
					offer(n, mv, nx, ty, nz, k, f1);
				}
			}

			// --- Parkour (sprint-saut) : seulement si utile (vide ou montée) ---
			if (!cfg.parkour || !sprintOk || honeyBelow || n.kind != WorldModel.GROUND) continue;
			if (iceBelow && !cfg.iceParkour) continue;
			if (!m.passable(n.x, n.y + 2, n.z)) continue;
			double iceF = iceBelow ? 1.2 : 1.0;
			int gFlat = (int) (gapFlat() * iceF);
			int gDown = (int) (gapDown() * iceF);

			// Saut plat / descendant au-dessus du vide (inutile si la case voisine se marche)
			if (!nextWalk) {
				for (int dy = 0; dy >= -1; dy--) {
					int maxD = (dy == 0 ? gFlat : gDown) + 1;
					for (int D = 2; D <= maxD; D++) {
						int tx = n.x + dx * D, tz = n.z + dz * D;
						int k = m.kind(tx, n.y + dy, tz);
						if (k != WorldModel.GROUND && k != WorldModel.SURFACE) continue;
						double f1 = m.floorOf(k, tx, n.y + dy, tz);
						double df = f1 - f0;
						if (dy == 0 && Math.abs(df) > 0.6) continue;
						if (dy == -1 && (df > -0.4 || df < -(gDown > 4 ? 4.5 : 2.6))) continue;
						if (!clearPath(n, dx, dz, D, clearanceTop)) continue;
						Move mv = new Move(PARKOUR, D * ph.sprintJumpCost + 5 + 2);
						mv.gap = D; mv.sprint = true; mv.waterRun = k == WorldModel.SURFACE;
						offer(n, mv, tx, n.y + dy, tz, k, f1);
					}
				}
			}
			// Saut montant (+1..+maxAscend) avec élan
			int maxUp = ph.maxAscend;
			for (int R = 1; R <= maxUp; R++) {
				if (R >= 2 && ph.jumpPeak < R + 0.3) continue;
				int minD = 2;
				int maxD = gFlat + 1;
				for (int D = minD; D <= maxD; D++) {
					int tx = n.x + dx * D, tz = n.z + dz * D;
					int k = m.kind(tx, n.y + R, tz);
					if (k != WorldModel.GROUND) continue;
					double f1 = m.floorOf(k, tx, n.y + R, tz);
					double df = f1 - f0;
					if (df < R - 0.6 || df > Math.min(R + 0.35, ph.jumpPeak - 0.05)) continue;
					if (!clearPath(n, dx, dz, D, clearanceTop + R)) continue;
					Move mv = new Move(PARKOUR, D * ph.sprintJumpCost + 8 + R * 2);
					mv.gap = D; mv.sprint = true;
					offer(n, mv, tx, n.y + R, tz, k, f1);
				}
			}
		}
	}

	/** Course sur l'eau : horizontal uniquement, sprint forcé, pas de saut (on est dans l'eau). */
	private void expandSurface(GNode n) {
		double f0 = n.floor;
		boolean sprintOk = cfg.sprint && ph.canSprint;
		double walkBase = sprintOk ? ph.sprintCost : ph.walkCost;
		for (int[] d : DIRS) {
			int dx = d[0], dz = d[1];
			boolean diag = dx != 0 && dz != 0;
			if (diag && !cfg.diagonals) continue;
			int nx = n.x + dx, nz = n.z + dz;
			if (diag && (!m.passable(nx, n.y + 1, n.z) || !m.passable(n.x, n.y + 1, nz))) continue;
			double dirF = diag ? 1.4142 : 1.0;
			for (int ty = n.y + 1; ty >= n.y - 1; ty--) {
				int k = m.kind(nx, ty, nz);
				if (k == WorldModel.SURFACE && ty == n.y) {
					Move mv = new Move(WALK, ph.waterRunCost * dirF);
					mv.sprint = true; mv.waterRun = true;
					offer(n, mv, nx, ty, nz, k, ty + 0.875);
				} else if (k == WorldModel.GROUND) {
					double f1 = m.floorOf(k, nx, ty, nz);
					double df = f1 - f0;
					if (df <= stepTol && df >= -0.6) {
						Move mv = new Move(WALK, walkBase * dirF * Math.max(1, m.surfaceFactor(nx, ty, nz)) + m.cellExtra(nx, ty, nz));
						mv.sprint = sprintOk; mv.door = m.doorClosed(nx, ty, nz);
						offer(n, mv, nx, ty, nz, k, f1);
					} else if (df < -0.6 && df >= -1.4 && cfg.descend) {
						Move mv = new Move(DESCEND, m.fallTime(-df) + 1);
						mv.drop = -df;
						offer(n, mv, nx, ty, nz, k, f1);
					}
				} else if (k == WorldModel.WATER && ty <= n.y) {
					offer(n, new Move(SWIM, ph.swimCost * dirF + 2), nx, ty, nz, k, ty);
				} else if (k == WorldModel.CLIMB && ty == n.y) {
					offer(n, new Move(WALK, 5 * dirF + 2), nx, ty, nz, k, ty);
				}
			}
		}
	}

	/** Vérifie que le couloir de saut est libre jusqu'au pic (haut plafond si Jump Boost). */
	private boolean clearPath(GNode n, int dx, int dz, int D, int top) {
		for (int c = 1; c < D; c++) {
			int cx = n.x + dx * c, cz = n.z + dz * c;
			for (int k = 1; k <= top; k++) {
				if (!m.passable(cx, n.y + k, cz)) return false;
			}
		}
		return true;
	}

	private void expandFly(GNode n) {
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
			if (dx == 0 && dy == 0 && dz == 0) continue;
			int nx = n.x + dx, ny = n.y + dy, nz = n.z + dz;
			int k = m.kind(nx, ny, nz);
			if (k == WorldModel.INVALID) continue;
			double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
			offer(n, new Move(FLY, ph.flyCost * dist), nx, ny, nz, k, ny);
		}
	}

	/** Lissage « string-pulling » : un segment droit praticable devient une seule consigne. */
	private boolean straightOk(GNode a, GNode b, List<GNode> chain, int ia, int ib) {
		if (a.kind != WorldModel.GROUND || b.kind != WorldModel.GROUND || a.y != b.y) return false;
		if (b.move == null || b.move.type != WALK || b.move.door || b.move.waterRun) return false;
		for (int t = ia + 1; t <= ib; t++) {
			GNode c = chain.get(t);
			if (c.kind != WorldModel.GROUND || c.y != a.y || c.move == null || c.move.type != WALK || c.move.door || c.move.waterRun) return false;
		}
		double ax = a.x + 0.5, az = a.z + 0.5, bx = b.x + 0.5, bz = b.z + 0.5;
		double dist = Math.hypot(bx - ax, bz - az);
		int steps = (int) Math.ceil(dist / 0.3);
		for (int s = 1; s <= steps; s++) {
			double t = (double) s / steps;
			int cx = (int) Math.floor(ax + (bx - ax) * t), cz = (int) Math.floor(az + (bz - az) * t);
			int k = m.kind(cx, a.y, cz);
			if (k != WorldModel.GROUND) return false;
			double f = m.floorOf(k, cx, a.y, cz);
			if (Math.abs(f - a.floor) > stepTol + 0.05) return false;
			if (m.doorClosed(cx, a.y, cz)) return false;
			if (m.cellExtra(cx, a.y, cz) > 12) return false;
		}
		return true;
	}

	private void finish(GNode end, boolean partial) {
		result = new PathResult();
		result.partial = partial;
		result.expansions = expansions;
		result.timeMs = System.currentTimeMillis() - startMs;
		List<GNode> chain = new ArrayList<>();
		GNode cur = end;
		while (cur != null) { chain.add(cur); cur = cur.parent; }
		java.util.Collections.reverse(chain);

		if (cfg.smoothPath && chain.size() > 2) {
			boolean sprintOk = cfg.sprint && ph.canSprint;
			List<GNode> out = new ArrayList<>(chain.size());
			out.add(chain.get(0));
			int i = 0, removed = 0;
			while (i < chain.size() - 1) {
				int bestIdx = i + 1;
				int lim = Math.min(chain.size() - 1, i + 24);
				for (int j = lim; j > i + 1; j--) {
					if (straightOk(chain.get(i), chain.get(j), chain, i, j)) { bestIdx = j; break; }
				}
				GNode b = chain.get(bestIdx);
				if (bestIdx > i + 1) {
					GNode a = chain.get(i);
					double dist = Math.hypot(b.x - a.x, b.z - a.z);
					Move mv = new Move(WALK, dist * (sprintOk ? ph.sprintCost : ph.walkCost));
					mv.sprint = sprintOk;
					b.move = mv;
					removed += bestIdx - i - 1;
				}
				out.add(b);
				i = bestIdx;
			}
			chain = out;
			result.smoothed = removed;
		}

		for (int i = 0; i < chain.size(); i++) {
			GNode g = chain.get(i);
			result.nodes.add(new BlockPos(g.x, g.y, g.z));
			if (g.move != null) { result.moves.add(g.move); result.cost += g.move.cost; }
		}
		state = partial ? State.PARTIAL : State.FOUND;
		if (end == start && !partial) state = State.FAILED;
	}

	public PathResult result() { return result; }
}
