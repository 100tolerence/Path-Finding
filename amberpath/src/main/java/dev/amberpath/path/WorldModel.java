package dev.amberpath.path;

import dev.amberpath.config.AmberConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;
import java.util.Set;

/**
 * Modèle du monde : classification des blocs + physique de déplacement issue de
 * {@link PlayerPhysics} (effets, enchantements, attributs live). Dalles, escaliers,
 * trappes, neige, sable des âmes, miel, glace, magma, lave (selon Résistance au feu),
 * eau (Depth Strider / Dolphins Grace), échelles, échafaudages, chutes (budget santé).
 *
 * Skyblock : course sur l'eau (SURFACE) à très haute ✦ Speed, auto-step, Frost Walker,
 * têtes de joueur (blocs custom), neige poudreuse (bottes cuir), garde anti-void
 * (aucune chute sans atterrissage trouvé dans la profondeur de scan).
 *
 * Performances : caches « direct-mapped » sans allocation (états de blocs + types de
 * nœuds + hauteurs de sol), invalidés toutes les 200 ms et à chaque changement connu.
 */
public class WorldModel {
	public static final int INVALID = 0, GROUND = 1, WATER = 2, LAVA = 3, CLIMB = 4, FLY = 5, SURFACE = 6;

	public final ClientLevel level;
	public final AmberConfig cfg;
	public PlayerPhysics phys;
	public final Set<Long> tempBlocked;
	private final MutableBlockPos mp = new MutableBlockPos();
	private final int minY, maxY;

	// --- Caches ---
	private static final int ST_MASK = 4095, K_MASK = 8191;
	private final long[] stKeys = new long[ST_MASK + 1];
	private final BlockState[] stVals = new BlockState[ST_MASK + 1];
	private final long[] kKeys = new long[K_MASK + 1];
	private final int[] kKinds = new int[K_MASK + 1];
	private final double[] kFloors = new double[K_MASK + 1];
	private long cacheAt = System.currentTimeMillis();

	public WorldModel(ClientLevel level, Player player, AmberConfig cfg, Set<Long> tempBlocked) {
		this.level = level; this.cfg = cfg; this.tempBlocked = tempBlocked;
		this.phys = PlayerPhysics.of(player, cfg);
		this.minY = level.getMinY();
		this.maxY = level.getMaxY();
		Arrays.fill(stKeys, Long.MIN_VALUE);
		Arrays.fill(kKeys, Long.MIN_VALUE);
	}

	/** Rafraîchit le profil physique (effets qui expirent, santé, enchantements, ✦ Speed). */
	public void refresh(Player player) { this.phys = PlayerPhysics.of(player, cfg); invalidate(); }

	/** Invalide les caches (blocs posés/cassés, profil changé). */
	public void invalidate() { Arrays.fill(stKeys, Long.MIN_VALUE); Arrays.fill(kKeys, Long.MIN_VALUE); cacheAt = System.currentTimeMillis(); }

	private void maybeInvalidate() {
		long now = System.currentTimeMillis();
		if (now - cacheAt > 200) invalidate();
	}

	private static int stIdx(long k) { return (int) ((k * 0x9E3779B97F4A7C15L) >>> 40) & ST_MASK; }
	private static int kIdx(long k) { return (int) ((k * 0x9E3779B97F4A7C15L) >>> 39) & K_MASK; }

	private BlockPos p(int x, int y, int z) { return mp.set(x, y, z); }

	public boolean loaded(int x, int y, int z) { return y >= minY && y < maxY && level.isLoaded(p(x, y, z)); }

	public BlockState st(int x, int y, int z) {
		long k = BlockPos.asLong(x, y, z);
		int i = stIdx(k);
		if (stKeys[i] == k) return stVals[i];
		BlockState s = level.getBlockState(p(x, y, z));
		stKeys[i] = k; stVals[i] = s;
		return s;
	}

	public boolean water(int x, int y, int z) { return st(x, y, z).getFluidState().is(FluidTags.WATER); }
	public boolean lava(int x, int y, int z) { return st(x, y, z).getFluidState().is(FluidTags.LAVA); }

	public boolean climb(int x, int y, int z) {
		BlockState s = st(x, y, z);
		Block b = s.getBlock();
		return s.is(BlockTags.CLIMBABLE) || b == Blocks.SCAFFOLDING || b == Blocks.TWISTING_VINES
				|| b == Blocks.TWISTING_VINES_PLANT || b == Blocks.WEEPING_VINES || b == Blocks.WEEPING_VINES_PLANT;
	}
	public boolean scaff(int x, int y, int z) { return st(x, y, z).getBlock() == Blocks.SCAFFOLDING; }
	public boolean tall(int x, int y, int z) {
		BlockState s = st(x, y, z);
		Block b = s.getBlock();
		return s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || b instanceof IronBarsBlock;
	}

	/** Le corps peut-il occuper cette cellule ? */
	public boolean passable(int x, int y, int z) {
		if (!loaded(x, y, z)) return false;
		if (phys.spectator) return true; // noclip
		BlockState s = st(x, y, z);
		if (s.isAir()) return true;
		FluidState f = s.getFluidState();
		if (!f.isEmpty()) {
			if (f.is(FluidTags.WATER)) return cfg.swim;
			if (f.is(FluidTags.LAVA)) return phys.fireResist && cfg.swim;
			return false;
		}
		Block b = s.getBlock();
		if (climb(x, y, z)) return cfg.climb;
		if (s.is(BlockTags.DOORS)) {
			if (s.getValue(DoorBlock.OPEN)) return true;
			if (b == Blocks.IRON_DOOR) return false;
			return cfg.doors;
		}
		if (s.is(BlockTags.FENCE_GATES)) return s.getValue(FenceGateBlock.OPEN) || cfg.doors;
		if (s.is(BlockTags.TRAPDOORS)) return s.getValue(TrapDoorBlock.OPEN);
		if (tall(x, y, z)) return false;
		if (b instanceof WallSkullBlock) return true; // tête murale (blocs custom Skyblock)
		if (b == Blocks.CACTUS) return false;
		if (b == Blocks.FIRE || b == Blocks.SOUL_FIRE) return phys.fireResist;
		if (b == Blocks.WITHER_ROSE) return !cfg.avoidDanger;
		if (b == Blocks.POWDER_SNOW) return false;
		if (b == Blocks.SWEET_BERRY_BUSH || b == Blocks.COBWEB) return true;
		if (b instanceof SnowLayerBlock) return s.getValue(SnowLayerBlock.LAYERS) <= 4;
		VoxelShape shape = s.getCollisionShape(level, p(x, y, z));
		if (shape.isEmpty()) return true;
		return shape.max(Direction.Axis.Y) <= 0.5625;
	}

	public boolean doorClosed(int x, int y, int z) {
		BlockState s = st(x, y, z);
		return (s.is(BlockTags.DOORS) && s.getBlock() != Blocks.IRON_DOOR && !s.getValue(DoorBlock.OPEN))
				|| (s.is(BlockTags.FENCE_GATES) && !s.getValue(FenceGateBlock.OPEN));
	}

	/** Hauteur du sol pour un nœud « pieds » en y (support en y-1). NaN si pas de support. */
	public double supportFloor(int x, int y, int z) {
		if (!loaded(x, y - 1, z)) return Double.NaN;
		BlockState s = st(x, y - 1, z);
		if (s.isAir()) return Double.NaN;
		FluidState f = s.getFluidState();
		if (!f.isEmpty()) {
			// Frost Walker : l'eau source gèle sous les pas → sol plein.
			if (f.is(FluidTags.WATER) && phys.frostWalker > 0 && cfg.frostWalkerWalk && f.isSource()) return y;
			return Double.NaN;
		}
		Block b = s.getBlock();
		if (b instanceof PowderSnowBlock) return phys.leatherBoots ? y : Double.NaN;
		if (b instanceof WallSkullBlock) return Double.NaN;
		if (s.is(BlockTags.DOORS)) return Double.NaN;
		if (tall(x, y - 1, z)) return y + 0.5; // sommet 1.5
		if (s.is(BlockTags.FENCE_GATES)) return s.getValue(FenceGateBlock.OPEN) ? Double.NaN : y + 0.5;
		if (s.is(BlockTags.TRAPDOORS)) {
			if (s.getValue(TrapDoorBlock.OPEN)) return Double.NaN;
			return s.getValue(TrapDoorBlock.HALF) == Half.TOP ? y : y - 0.8125; // trappe basse = 3/16
		}
		if (s.is(BlockTags.SLABS)) return s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? y - 0.5 : y;
		if (s.is(BlockTags.STAIRS)) return s.getValue(StairBlock.HALF) == Half.TOP ? y : y - 0.5;
		if (b == Blocks.SCAFFOLDING) return y;
		if (b instanceof SnowLayerBlock) {
			int l = s.getValue(SnowLayerBlock.LAYERS);
			return l >= 8 ? y : (y - 1) + l * 0.125;
		}
		if (b instanceof SkullBlock) return (y - 1) + 0.5; // tête au sol
		if (b == Blocks.FARMLAND || b == Blocks.DIRT_PATH || b == Blocks.HONEY_BLOCK) return y - 0.0625;
		if (b == Blocks.SOUL_SAND) return y - 0.125;
		if (b == Blocks.CACTUS) return Double.NaN;
		if (s.isCollisionShapeFullBlock(level, p(x, y - 1, z))) return y;
		VoxelShape shape = s.getCollisionShape(level, p(x, y - 1, z));
		if (shape.isEmpty()) return Double.NaN;
		double h = shape.max(Direction.Axis.Y);
		return h <= 1.0 ? (y - 1) + h : Double.NaN;
	}

	public boolean magma(int x, int y, int z) { return st(x, y, z).getBlock() == Blocks.MAGMA_BLOCK; }
	public boolean honey(int x, int y, int z) { return st(x, y, z).getBlock() == Blocks.HONEY_BLOCK; }
	public boolean ice(int x, int y, int z) { return st(x, y - 1, z).is(BlockTags.ICE); }
	public boolean soulSand(int x, int y, int z) { return st(x, y, z).getBlock() == Blocks.SOUL_SAND; }
	public boolean soulBlock(int x, int y, int z) { return st(x, y, z).is(BlockTags.SOUL_SPEED_BLOCKS); }
	public boolean dripstone(int x, int y, int z) {
		return st(x, y, z).getBlock() == Blocks.POINTED_DRIPSTONE || st(x, y - 1, z).getBlock() == Blocks.POINTED_DRIPSTONE;
	}
	/** Magma inoffensif (Résistance au feu ou Frost Walker). */
	public boolean magmaSafe() { return phys.fireResist || phys.frostWalker > 0; }

	/** Atterrissage qui annule les dégâts de chute (eau, slime, toile, poudreuse, baies, lianes…). */
	public boolean safeLanding(int x, int y, int z) {
		if (water(x, y, z) || water(x, y - 1, z)) return true;
		Block b = st(x, y - 1, z).getBlock();
		if (b == Blocks.SLIME_BLOCK || b == Blocks.POWDER_SNOW || b == Blocks.COBWEB) return true;
		Block c = st(x, y, z).getBlock();
		if (c == Blocks.COBWEB || c == Blocks.POWDER_SNOW || c == Blocks.SWEET_BERRY_BUSH) return true;
		return st(x, y, z).is(BlockTags.FALL_DAMAGE_RESETTING) || st(x, y - 1, z).is(BlockTags.FALL_DAMAGE_RESETTING);
	}
	public boolean hayHoney(int x, int y, int z) {
		Block b = st(x, y - 1, z).getBlock();
		return b == Blocks.HAY_BLOCK || b == Blocks.HONEY_BLOCK;
	}

	/** Multiplicateur de dégâts de chute pour un atterrissage en (x,y,z). */
	public double landingMult(int x, int y, int z) {
		return PlayerPhysics.landingMult(safeLanding(x, y, z), hayHoney(x, y, z), dripstone(x, y, z));
	}

	/** Type de nœud valide en (x,y,z), ou INVALID (avec cache). */
	public int kind(int x, int y, int z) {
		maybeInvalidate();
		long k = BlockPos.asLong(x, y, z);
		int i = kIdx(k);
		if (kKeys[i] == k) return kKinds[i];
		int kind = computeKind(x, y, z);
		double floor = kind == GROUND ? supportFloor(x, y, z) : kind == SURFACE ? y + 0.875 : y;
		kKeys[i] = k; kKinds[i] = kind; kFloors[i] = floor;
		return kind;
	}

	private int computeKind(int x, int y, int z) {
		if (!loaded(x, y, z)) return INVALID;
		if (tempBlocked.contains(BlockPos.asLong(x, y, z))) return INVALID;
		if ((phys.flying && cfg.flyAuto) || phys.spectator) {
			return passable(x, y, z) ? FLY : INVALID;
		}
		if (water(x, y, z)) {
			// Course sur l'eau : surface libre + corps dégagé, à très haute ✦ Speed.
			if (phys.sbWaterWalk && !water(x, y + 1, z) && passable(x, y + 1, z) && passable(x, y + 2, z)) return SURFACE;
			return cfg.swim && passable(x, y + 1, z) ? WATER : INVALID;
		}
		if (lava(x, y, z)) return phys.fireResist && cfg.swim && passable(x, y + 1, z) ? LAVA : INVALID;
		if (climb(x, y, z)) return cfg.climb && passable(x, y + 1, z) ? CLIMB : INVALID;
		if (!passable(x, y, z) || !passable(x, y + 1, z)) return INVALID;
		double f = supportFloor(x, y, z);
		if (Double.isNaN(f)) return INVALID;
		if (f > y + 0.001 && !passable(x, y + 2, z)) return INVALID; // sommet de clôture
		if (cfg.avoidDanger && !cfg.sneakMagma && !magmaSafe() && magma(x, y - 1, z)) return INVALID;
		if (cfg.avoidDanger && dripstone(x, y, z)) return INVALID;
		return GROUND;
	}

	public double floorOf(int kind, int x, int y, int z) {
		if (kind == SURFACE) return y + 0.875;
		if (kind != GROUND) return y;
		maybeInvalidate();
		long k = BlockPos.asLong(x, y, z);
		int i = kIdx(k);
		if (kKeys[i] == k && kKinds[i] == GROUND) return kFloors[i];
		return supportFloor(x, y, z);
	}

	/** Coût additionnel de traversée d'une cellule (danger, ralentissement, porte…). */
	public double cellExtra(int x, int y, int z) {
		BlockState s = st(x, y, z);
		Block b = s.getBlock();
		double extra = 0;
		if (b == Blocks.SWEET_BERRY_BUSH) extra += cfg.avoidDanger ? 30 : 10;
		if (b == Blocks.COBWEB) extra += 25;
		if (b instanceof SnowLayerBlock) extra += s.getValue(SnowLayerBlock.LAYERS) * 1.2;
		if (b instanceof WallSkullBlock) extra += 1.5;
		if (doorClosed(x, y, z)) extra += 8;
		if (water(x, y, z) && water(x, y + 1, z)) extra += 2; // immergé
		if (lava(x, y, z)) extra += 8;
		return extra;
	}

	/** Facteur de vitesse de surface (sable des âmes + Soul Speed, miel, glace, magma accroupi). */
	public double surfaceFactor(int x, int y, int z) {
		BlockState below = st(x, y - 1, z);
		Block b = below.getBlock();
		if (below.is(BlockTags.SOUL_SPEED_BLOCKS)) {
			if (phys.soulSpeed > 0) return 1.0 / (1.0 + 0.105 * phys.soulSpeed);
			return b == Blocks.SOUL_SAND ? 2.0 : 1.0;
		}
		if (b == Blocks.HONEY_BLOCK) return 2.4;
		if (below.is(BlockTags.ICE)) return 0.9;
		if (magma(x, y - 1, z) && cfg.sneakMagma && !magmaSafe()) return 1.0 / phys.sneakFactor;
		return 1.0;
	}

	/** Temps de chute (ticks) — Slow Falling ralentit fortement. */
	public double fallTime(double d) {
		if (phys.slowFalling) return d / 0.15 + 6;
		return 2.2 * Math.sqrt(Math.max(0, d)) + 3;
	}

	/** Hauteur de chute maximale autorisée par la config + le budget santé live + le scan. */
	public double maxDrop() {
		if (!cfg.descend) return 0;
		double free = Math.max(cfg.maxFall, phys.safeFall);
		double v = cfg.riskyFalls ? Math.max(free, phys.maxSafeDrop(cfg.keepHearts)) : free;
		return Math.min(v, cfg.maxFallScan);
	}
}
