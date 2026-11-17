package dev.amberpath.path;

import dev.amberpath.config.AmberConfig;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* incrémental (budget temps par tick) sur grille de blocs, avec mouvements
 * calibrés sur la physique Minecraft. Poids configurable (optimal ↔ rapide).
 * Si l'objectif est hors chunks chargés / budget, renvoie un chemin partiel
 * vers la frontière la plus proche du but (replanification automatique).
 */
public class PathEngine {
	public enum State { SEARCHING, FOUND, PARTIAL, FAILED }

	public static final int WALK = 0, ASCEND = 1, DESCEND = 2, PARKOUR = 3, SWIM = 4,
			CLIMB_UP = 5, CLIMB_DOWN = 6, SCAFF_DOWN = 7, FLY = 8;

	public static class Move {
		public int type; public double cost; public int gap; public boolean sprint; public boolean door;
		Move(int type, double cost) { this.type = type; this.cost = cost; }
	}

	public static class PathResult {
		public List<BlockPos> nodes = new ArrayList<>();
		public List<Move> moves = new ArrayList<>();
		public double cost; public boolean partial; public int expansions; public long timeMs;
	}

	public static class Goal {
		public int mode; public int x, z; public Integer y; public int radius;
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
	private final Goal goal;
	private final GNode start;
	private final Map<Long, GNode> all = new HashMap<>();
	private final PriorityQueue<GNode> open;
	private GNode best;
	public State state = State.SEARCHING;
	public int expansions = 0;
	private final long startMs = System.currentTimeMillis();
	private PathResult result;

	private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

	public PathEngine(WorldModel m, AmberConfig cfg, BlockPos from, Goal goal) {
		this.m = m; this.cfg = cfg; this.goal = goal;
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
		return d2 * 2.6 + dy * 2.2;
	}

	private boolean isGoal(GNode n) {
		if (n.x != goal.x && goal.mode != 2) return false;
		if (n.z != goal.z && goal.mode != 2) return false;
		switch (goal.mode) {
			case 0: // se tenir dessus
				if (n.x != goal.x || n.z != goal.z || n.kind != WorldModel.GROUND) return false;
				return goal.y == null || Math.abs(n.floor - (goal.y + 1)) < 0.75;
			case 1: // atteindre
				return n.x == goal.x && n.z == goal.z && (goal.y == null || n.y == goal.y);
			default: // approcher
				double dx = n.x - goal.x, dz = n.z - goal.z;
				if (dx * dx + dz * dz > (double) goal.radius * goal.radius) return false;
				return goal.y == null || Math.abs(n.y - goal.y) <= goal.radius;
		}
	}

	/** Étend la recherche dans la limite du budget temps. */
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

	private void expand(GNode n) {
		if (n.kind == WorldModel.FLY) { expandFly(n); return; }
		boolean sprintOk = cfg.sprint && m.canSprint;
		boolean honeyBelow = m.honey(n.x, n.y - 1, n.z);
		boolean iceBelow = m.ice(n.x, n.y - 1, n.z);
		double f0 = n.floor;

		// Mouvements verticaux (nage / escalade)
		if (n.kind == WorldModel.CLIMB) {
			int ku = m.kind(n.x, n.y + 1, n.z);
			if (ku == WorldModel.CLIMB) offer(n, new Move(CLIMB_UP, 8), n.x, n.y + 1, n.z, ku, n.y + 1);
			else if (ku == WorldModel.GROUND) { Move mv = new Move(CLIMB_UP, 11); offer(n, mv, n.x, n.y + 1, n.z, ku, m.floorOf(ku, n.x, n.y + 1, n.z)); }
			int kd = m.kind(n.x, n.y - 1, n.z);
			if (kd == WorldModel.CLIMB) {
				boolean sc = m.scaff(n.x, n.y, n.z);
				offer(n, new Move(sc ? SCAFF_DOWN : CLIMB_DOWN, sc ? 3.5 : 6.7), n.x, n.y - 1, n.z, kd, n.y - 1);
			}
		}
		if (n.kind == WorldModel.WATER || n.kind == WorldModel.LAVA) {
			double base = n.kind == WorldModel.LAVA ? 11 : (m.water(n.x, n.y + 1, n.z) ? 6.0 : 4.8);
			int ku = m.kind(n.x, n.y + 1, n.z);
			if (ku == WorldModel.WATER || ku == WorldModel.LAVA) offer(n, new Move(SWIM, base + 1), n.x, n.y + 1, n.z, ku, n.y + 1);
			else if (ku == WorldModel.GROUND && m.floorOf(ku, n.x, n.y + 1, n.z) <= n.y + 1.2)
				offer(n, new Move(ASCEND, 9), n.x, n.y + 1, n.z, ku, m.floorOf(ku, n.x, n.y + 1, n.z));
			int kd = m.kind(n.x, n.y - 1, n.z);
			if (kd == WorldModel.WATER || kd == WorldModel.LAVA) offer(n, new Move(SWIM, base), n.x, n.y - 1, n.z, kd, n.y - 1);
		}

		for (int[] d : DIRS) {
			int dx = d[0], dz = d[1];
			boolean diag = dx != 0 && dz != 0;
			if (diag && !cfg.diagonals) continue;
			int nx = n.x + dx, nz = n.z + dz;
			if (diag && (!m.passable(nx, n.y, n.z) || !m.passable(nx, n.y + 1, n.z)
					|| !m.passable(n.x, n.y, nz) || !m.passable(n.x, n.y + 1, nz))) continue;
			double dirF = diag ? 1.4142 : 1.0;

			if (n.kind == WorldModel.WATER || n.kind == WorldModel.LAVA) {
				double base = n.kind == WorldModel.LAVA ? 11 : (m.water(n.x, n.y + 1, n.z) ? 6.0 : 4.8);
				for (int ty = n.y + 1; ty >= n.y - 1; ty--) {
					int k = m.kind(nx, ty, nz);
					if (k == WorldModel.WATER || k == WorldModel.LAVA)
						offer(n, new Move(SWIM, base * dirF + m.cellExtra(nx, ty, nz)), nx, ty, nz, k, ty);
					else if (k == WorldModel.GROUND && ty >= n.y && m.floorOf(k, nx, ty, nz) - n.y <= 1.2)
						offer(n, new Move(ASCEND, 9 * dirF), nx, ty, nz, k, m.floorOf(k, nx, ty, nz));
				}
				continue;
			}

			// Marche / montée / chute vers la colonne voisine
			int maxDrop = cfg.riskyFalls ? 20 : Math.max(cfg.maxFall, 3);
			for (int ty = n.y + 1; ty >= n.y - maxDrop; ty--) {
				if (ty < n.y && !m.passable(nx, ty + 1, nz)) break; // gaine de chute obstruée
				int k = m.kind(nx, ty, nz);
				if (k == WorldModel.INVALID) continue;
				double f1 = m.floorOf(k, nx, ty, nz);
				if (k == WorldModel.WATER || k == WorldModel.LAVA) {
					if (ty <= n.y && cfg.swim) {
						double drop = Math.max(0, f0 - ty);
						offer(n, new Move(SWIM, WorldModel.fallTime(drop) + 3), nx, ty, nz, k, ty);
					}
					continue;
				}
				if (k == WorldModel.CLIMB) {
					if (ty == n.y) offer(n, new Move(WALK, 5 * dirF + 2), nx, ty, nz, k, ty);
					continue;
				}
				// GROUND
				double df = f1 - f0;
				if (ty == n.y && Math.abs(df) <= 0.6) {
					double slow = m.surfaceFactor(nx, ty, nz) * m.surfaceFactor(n.x, n.y, n.z);
					double cost = (sprintOk ? 3.56 : 4.63) * dirF * Math.max(1, slow) + m.cellExtra(nx, ty, nz);
					Move mv = new Move(WALK, cost);
					mv.sprint = sprintOk; mv.door = m.doorClosed(nx, ty, nz);
					offer(n, mv, nx, ty, nz, k, f1);
				} else if (df > 0.6 && df <= 1.25 && ty == n.y + 1 && !honeyBelow && m.passable(n.x, n.y + 2, n.z)) {
					Move mv = new Move(ASCEND, sprintOk ? 10 : 11);
					mv.sprint = sprintOk;
					offer(n, mv, nx, ty, nz, k, f1);
				} else if (df < -0.6 && cfg.descend) {
					double drop = -df;
					boolean safe = m.safeLanding(nx, ty, nz);
					if (drop > cfg.maxFall && !safe && !cfg.riskyFalls) continue;
					double penalty = 0;
					if (drop > 3.5 && !safe) penalty = 25 * (drop - 3);
					Move mv = new Move(DESCEND, WorldModel.fallTime(drop) + 3 + penalty);
					offer(n, mv, nx, ty, nz, k, f1);
				}
			}

			// Parkour : sprint-saut au-dessus du vide
			if (cfg.parkour && sprintOk && !honeyBelow && !iceBelow && n.kind == WorldModel.GROUND
					&& m.passable(n.x, n.y + 2, n.z)) {
				for (int dy = 0; dy >= -1; dy--) {
					int maxD = dy == 0 ? cfg.maxGap + 1 : cfg.maxGap + 2;
					for (int D = 2; D <= maxD; D++) {
						int tx = n.x + dx * D, tz = n.z + dz * D;
						int k = m.kind(tx, n.y + dy, tz);
						if (k != WorldModel.GROUND) continue;
						double f1 = m.floorOf(k, tx, n.y + dy, tz);
						double df = f1 - f0;
						if (dy == 0 && Math.abs(df) > 0.6) continue;
						if (dy == -1 && (df > -0.4 || df < -2.6)) continue;
						boolean clear = true;
						for (int c = 1; c < D; c++) {
							int cx = n.x + dx * c, cz = n.z + dz * c;
							if (!m.passable(cx, n.y + 1, cz) || !m.passable(cx, n.y + 2, cz)) { clear = false; break; }
						}
						if (!clear) continue;
						Move mv = new Move(PARKOUR, D * 2.95 + 5 + 2);
						mv.gap = D; mv.sprint = true;
						offer(n, mv, tx, n.y + dy, tz, k, f1);
					}
				}
				// saut 1 bloc vers le haut avec élan (1-gap +1)
				if (cfg.maxGap >= 1) {
					int tx = n.x + dx * 2, tz = n.z + dz * 2;
					int k = m.kind(tx, n.y + 1, tz);
					if (k == WorldModel.GROUND && m.passable(n.x + dx, n.y + 1, n.z + dz) && m.passable(n.x + dx, n.y + 2, n.z + dz)) {
						double f1 = m.floorOf(k, tx, n.y + 1, tz);
						if (f1 - f0 > 0.6 && f1 - f0 <= 1.6) {
							Move mv = new Move(PARKOUR, 2 * 2.95 + 8); mv.gap = 2; mv.sprint = true;
							offer(n, mv, tx, n.y + 1, tz, k, f1);
						}
					}
				}
			}
		}
	}

	private void expandFly(GNode n) {
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
			if (dx == 0 && dy == 0 && dz == 0) continue;
			int nx = n.x + dx, ny = n.y + dy, nz = n.z + dz;
			int k = m.kind(nx, ny, nz);
			if (k == WorldModel.INVALID) continue;
			double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
			offer(n, new Move(FLY, 2.1 * dist), nx, ny, nz, k, ny);
		}
	}

	private void finish(GNode end, boolean partial) {
		result = new PathResult();
		result.partial = partial;
		result.expansions = expansions;
		result.timeMs = System.currentTimeMillis() - startMs;
		List<GNode> chain = new ArrayList<>();
		GNode cur = end;
		while (cur != null) { chain.add(cur); cur = cur.parent; }
		for (int i = chain.size() - 1; i >= 0; i--) {
			GNode g = chain.get(i);
			result.nodes.add(new BlockPos(g.x, g.y, g.z));
			if (g.move != null) { result.moves.add(g.move); result.cost += g.move.cost; }
		}
		state = partial ? State.PARTIAL : State.FOUND;
		if (end == start && !partial) state = State.FAILED;
	}

	public PathResult result() { return result; }
}
