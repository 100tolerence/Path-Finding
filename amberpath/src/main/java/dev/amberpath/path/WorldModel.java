package dev.amberpath.path;

import dev.amberpath.config.AmberConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Set;

/**
 * Modèle du monde : classification des blocs et physique de déplacement Minecraft.
 * Vitesses calibrées (ticks/bloc) : marche 4.63, sprint 3.56, sprint-saut ~2.95,
 * nage ~6, échelle montante 8, descendante 6.7, échafaudage 3.5, vol 2.1.
 */
public class WorldModel {
	public static final int INVALID = 0, GROUND = 1, WATER = 2, LAVA = 3, CLIMB = 4, FLY = 5;

	public final ClientLevel level;
	public final AmberConfig cfg;
	public final boolean fireResist;
	public final boolean canSprint;
	public final boolean flying;
	public final Set<Long> tempBlocked;
	private final MutableBlockPos mp = new MutableBlockPos();
	private final int minY, maxY;

	public WorldModel(ClientLevel level, Player player, AmberConfig cfg, Set<Long> tempBlocked) {
		this.level = level; this.cfg = cfg; this.tempBlocked = tempBlocked;
		this.fireResist = player.hasEffect(MobEffects.FIRE_RESISTANCE);
		this.canSprint = player.getFoodData().getFoodLevel() > 6 || player.isCreative();
		this.flying = player.getAbilities().flying;
		this.minY = level.getMinY();
		this.maxY = level.getMaxY();
	}

	private BlockPos p(int x, int y, int z) { return mp.set(x, y, z); }

	public boolean loaded(int x, int y, int z) {
		return y >= minY && y < maxY && level.isLoaded(p(x, y, z));
	}
	public BlockState st(int x, int y, int z) { return level.getBlockState(p(x, y, z)); }
	public boolean water(int x, int y, int z) { return level.getFluidState(p(x, y, z)).is(FluidTags.WATER); }
	public boolean lava(int x, int y, int z) { return level.getFluidState(p(x, y, z)).is(FluidTags.LAVA); }

	public boolean climb(int x, int y, int z) {
		BlockState s = st(x, y, z);
		Block b = s.getBlock();
		return s.is(BlockTags.CLIMBABLE) || b == Blocks.SCAFFOLDING || b == Blocks.TWISTING_VINES || b == Blocks.WEEPING_VINES;
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
		BlockState s = st(x, y, z);
		if (s.isAir()) return true;
		FluidState f = s.getFluidState();
		if (!f.isEmpty()) {
			if (f.is(FluidTags.WATER)) return cfg.swim;
			if (f.is(FluidTags.LAVA)) return fireResist && cfg.swim;
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
		if (b == Blocks.CACTUS) return false;
		if (b == Blocks.FIRE || b == Blocks.SOUL_FIRE) return fireResist;
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
		if (!s.getFluidState().isEmpty()) return Double.NaN;
		Block b = s.getBlock();
		if (s.is(BlockTags.DOORS)) return Double.NaN;
		if (tall(x, y - 1, z)) return y + 0.5; // sommet 1.5
		if (s.is(BlockTags.FENCE_GATES)) return s.getValue(FenceGateBlock.OPEN) ? Double.NaN : y + 0.5;
		if (s.is(BlockTags.TRAPDOORS)) {
			if (s.getValue(TrapDoorBlock.OPEN)) return Double.NaN;
			return s.getValue(TrapDoorBlock.HALF) == Half.TOP ? y : y - 0.5;
		}
		if (s.is(BlockTags.SLABS)) return s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? y - 0.5 : y;
		if (s.is(BlockTags.STAIRS)) return s.getValue(StairBlock.HALF) == Half.TOP ? y : y - 0.5;
		if (b == Blocks.SCAFFOLDING) return y;
		if (b instanceof SnowLayerBlock) {
			int l = s.getValue(SnowLayerBlock.LAYERS);
			return l <= 4 ? (y - 1) + l * 0.125 : Double.NaN;
		}
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
	public boolean ice(int x, int y, int z) { return st(x, y, z).is(BlockTags.ICE); }
	public boolean soulSand(int x, int y, int z) { return st(x, y, z).getBlock() == Blocks.SOUL_SAND; }

	/** Atterrissage qui annule/réduit les dégâts de chute. */
	public boolean safeLanding(int x, int y, int z) {
		if (water(x, y, z) || water(x, y - 1, z)) return true;
		Block b = st(x, y - 1, z).getBlock();
		if (b == Blocks.HAY_BLOCK || b == Blocks.SLIME_BLOCK || b == Blocks.POWDER_SNOW || b == Blocks.COBWEB) return true;
		Block c = st(x, y, z).getBlock();
		return c == Blocks.COBWEB || c == Blocks.POWDER_SNOW || st(x, y, z).is(BlockTags.FALL_DAMAGE_RESETTING);
	}

	/** Type de nœud valide en (x,y,z), ou INVALID. */
	public int kind(int x, int y, int z) {
		if (!loaded(x, y, z)) return INVALID;
		if (tempBlocked.contains(BlockPos.asLong(x, y, z))) return INVALID;
		if (flying && cfg.flyAuto) {
			return passable(x, y, z) ? FLY : INVALID;
		}
		if (water(x, y, z)) return cfg.swim && passable(x, y + 1, z) ? WATER : INVALID;
		if (lava(x, y, z)) return fireResist && cfg.swim && passable(x, y + 1, z) ? LAVA : INVALID;
		if (climb(x, y, z)) return cfg.climb && passable(x, y + 1, z) ? CLIMB : INVALID;
		if (!passable(x, y, z) || !passable(x, y + 1, z)) return INVALID;
		double f = supportFloor(x, y, z);
		if (Double.isNaN(f)) return INVALID;
		if (f > y + 0.001 && !passable(x, y + 2, z)) return INVALID; // sommet de clôture
		if (cfg.avoidDanger && !cfg.sneakMagma && magma(x, y - 1, z)) return INVALID;
		return GROUND;
	}

	public double floorOf(int kind, int x, int y, int z) {
		return kind == GROUND ? supportFloor(x, y, z) : y;
	}

	/** Coût additionnel de traversée d'une cellule (danger, ralentissement, porte…). */
	public double cellExtra(int x, int y, int z) {
		BlockState s = st(x, y, z);
		Block b = s.getBlock();
		double extra = 0;
		if (b == Blocks.SWEET_BERRY_BUSH) extra += cfg.avoidDanger ? 30 : 10;
		if (b == Blocks.COBWEB) extra += 25;
		if (b instanceof SnowLayerBlock) extra += s.getValue(SnowLayerBlock.LAYERS) * 1.2;
		if (doorClosed(x, y, z)) extra += 8;
		if (water(x, y, z) && water(x, y + 1, z)) extra += 2; // immergé
		if (lava(x, y, z)) extra += 8;
		return extra;
	}

	public double surfaceFactor(int x, int y, int z) {
		Block b = st(x, y - 1, z).getBlock();
		if (b == Blocks.SOUL_SAND) return 2.1;
		if (b == Blocks.HONEY_BLOCK) return 2.4;
		if (st(x, y - 1, z).is(BlockTags.ICE)) return 0.95;
		if (magma(x, y - 1, z) && cfg.sneakMagma) return 2.6;
		return 1.0;
	}

	public static double fallTime(double d) { return 2.2 * Math.sqrt(Math.max(0, d)) + 3; }
}
