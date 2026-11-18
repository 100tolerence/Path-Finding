package dev.amberpath.path;

import dev.amberpath.config.AmberConfig;
import java.util.Arrays;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

public class WorldModel {
   public static final int INVALID = 0;
   public static final int GROUND = 1;
   public static final int WATER = 2;
   public static final int LAVA = 3;
   public static final int CLIMB = 4;
   public static final int FLY = 5;
   public static final int SURFACE = 6;
   public final ClientLevel level;
   public final AmberConfig cfg;
   public PlayerPhysics phys;
   public final Set<Long> tempBlocked;
   private final MutableBlockPos mp = new MutableBlockPos();
   private final int minY;
   private final int maxY;
   private static final int ST_MASK = 4095;
   private static final int K_MASK = 8191;
   private final long[] stKeys = new long[4096];
   private final BlockState[] stVals = new BlockState[4096];
   private final long[] kKeys = new long[8192];
   private final int[] kKinds = new int[8192];
   private final double[] kFloors = new double[8192];
   private long cacheAt = System.currentTimeMillis();

   public WorldModel(ClientLevel level, Player player, AmberConfig cfg, Set<Long> tempBlocked) {
      this.level = level;
      this.cfg = cfg;
      this.tempBlocked = tempBlocked;
      this.phys = PlayerPhysics.of(player, cfg);
      this.minY = level.getMinY();
      this.maxY = level.getMaxY();
      Arrays.fill(this.stKeys, Long.MIN_VALUE);
      Arrays.fill(this.kKeys, Long.MIN_VALUE);
   }

   public void refresh(Player player) {
      this.phys = PlayerPhysics.of(player, this.cfg);
      this.invalidate();
   }

   public void invalidate() {
      Arrays.fill(this.stKeys, Long.MIN_VALUE);
      Arrays.fill(this.kKeys, Long.MIN_VALUE);
      this.cacheAt = System.currentTimeMillis();
   }

   private void maybeInvalidate() {
      long now = System.currentTimeMillis();
      if (now - this.cacheAt > 200L) {
         this.invalidate();
      }
   }

   private static int stIdx(long k) {
      return (int)(k * -7046029254386353131L >>> 40) & 4095;
   }

   private static int kIdx(long k) {
      return (int)(k * -7046029254386353131L >>> 39) & 8191;
   }

   private BlockPos p(int x, int y, int z) {
      return this.mp.set(x, y, z);
   }

   public boolean loaded(int x, int y, int z) {
      return y >= this.minY && y < this.maxY && this.level.isLoaded(this.p(x, y, z));
   }

   public BlockState st(int x, int y, int z) {
      long k = BlockPos.asLong(x, y, z);
      int i = stIdx(k);
      if (this.stKeys[i] == k) {
         return this.stVals[i];
      } else {
         BlockState s = this.level.getBlockState(this.p(x, y, z));
         this.stKeys[i] = k;
         this.stVals[i] = s;
         return s;
      }
   }

   public boolean water(int x, int y, int z) {
      return this.st(x, y, z).getFluidState().is(FluidTags.WATER);
   }

   public boolean lava(int x, int y, int z) {
      return this.st(x, y, z).getFluidState().is(FluidTags.LAVA);
   }

   public boolean climb(int x, int y, int z) {
      BlockState s = this.st(x, y, z);
      Block b = s.getBlock();
      return s.is(BlockTags.CLIMBABLE)
         || b == Blocks.SCAFFOLDING
         || b == Blocks.TWISTING_VINES
         || b == Blocks.TWISTING_VINES_PLANT
         || b == Blocks.WEEPING_VINES
         || b == Blocks.WEEPING_VINES_PLANT;
   }

   public boolean scaff(int x, int y, int z) {
      return this.st(x, y, z).getBlock() == Blocks.SCAFFOLDING;
   }

   public boolean tall(int x, int y, int z) {
      BlockState s = this.st(x, y, z);
      Block b = s.getBlock();
      return s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || b instanceof IronBarsBlock;
   }

   public boolean passable(int x, int y, int z) {
      if (!this.loaded(x, y, z)) {
         return false;
      } else if (this.phys.spectator) {
         return true;
      } else {
         BlockState s = this.st(x, y, z);
         if (s.isAir()) {
            return true;
         } else {
            FluidState f = s.getFluidState();
            if (!f.isEmpty()) {
               if (f.is(FluidTags.WATER)) {
                  return this.cfg.swim;
               } else {
                  return !f.is(FluidTags.LAVA) ? false : this.phys.fireResist && this.cfg.swim;
               }
            } else {
               Block b = s.getBlock();
               if (this.climb(x, y, z)) {
                  return this.cfg.climb;
               } else if (s.is(BlockTags.DOORS)) {
                  if ((Boolean)s.getValue(DoorBlock.OPEN)) {
                     return true;
                  } else {
                     return b == Blocks.IRON_DOOR ? false : this.cfg.doors;
                  }
               } else if (!s.is(BlockTags.FENCE_GATES)) {
                  if (s.is(BlockTags.TRAPDOORS)) {
                     return (Boolean)s.getValue(TrapDoorBlock.OPEN);
                  } else if (this.tall(x, y, z)) {
                     return false;
                  } else if (b instanceof WallSkullBlock) {
                     return true;
                  } else if (b == Blocks.CACTUS) {
                     return false;
                  } else if (b == Blocks.FIRE || b == Blocks.SOUL_FIRE) {
                     return this.phys.fireResist;
                  } else if (b == Blocks.WITHER_ROSE) {
                     return !this.cfg.avoidDanger;
                  } else if (b == Blocks.POWDER_SNOW) {
                     return false;
                  } else if (b == Blocks.SWEET_BERRY_BUSH || b == Blocks.COBWEB) {
                     return true;
                  } else if (b instanceof SnowLayerBlock) {
                     return (Integer)s.getValue(SnowLayerBlock.LAYERS) <= 4;
                  } else {
                     VoxelShape shape = s.getCollisionShape(this.level, this.p(x, y, z));
                     return shape.isEmpty() ? true : shape.max(Axis.Y) <= 0.5625;
                  }
               } else {
                  return (Boolean)s.getValue(FenceGateBlock.OPEN) || this.cfg.doors;
               }
            }
         }
      }
   }

   public boolean doorClosed(int x, int y, int z) {
      BlockState s = this.st(x, y, z);
      return s.is(BlockTags.DOORS) && s.getBlock() != Blocks.IRON_DOOR && !(Boolean)s.getValue(DoorBlock.OPEN)
         || s.is(BlockTags.FENCE_GATES) && !(Boolean)s.getValue(FenceGateBlock.OPEN);
   }

   public double supportFloor(int x, int y, int z) {
      if (!this.loaded(x, y - 1, z)) {
         return Double.NaN;
      } else {
         BlockState s = this.st(x, y - 1, z);
         if (s.isAir()) {
            return Double.NaN;
         } else {
            FluidState f = s.getFluidState();
            if (!f.isEmpty()) {
               return f.is(FluidTags.WATER) && this.phys.frostWalker > 0 && this.cfg.frostWalkerWalk && f.isSource() ? y : Double.NaN;
            } else {
               Block b = s.getBlock();
               if (b instanceof PowderSnowBlock) {
                  return this.phys.leatherBoots ? y : Double.NaN;
               } else if (b instanceof WallSkullBlock) {
                  return Double.NaN;
               } else if (s.is(BlockTags.DOORS)) {
                  return Double.NaN;
               } else if (this.tall(x, y - 1, z)) {
                  return y + 0.5;
               } else if (s.is(BlockTags.FENCE_GATES)) {
                  return s.getValue(FenceGateBlock.OPEN) ? Double.NaN : y + 0.5;
               } else if (s.is(BlockTags.TRAPDOORS)) {
                  if ((Boolean)s.getValue(TrapDoorBlock.OPEN)) {
                     return Double.NaN;
                  } else {
                     return s.getValue(TrapDoorBlock.HALF) == Half.TOP ? y : y - 0.8125;
                  }
               } else if (s.is(BlockTags.SLABS)) {
                  return s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? y - 0.5 : y;
               } else if (s.is(BlockTags.STAIRS)) {
                  return s.getValue(StairBlock.HALF) == Half.TOP ? y : y - 0.5;
               } else if (b == Blocks.SCAFFOLDING) {
                  return y;
               } else if (b instanceof SnowLayerBlock) {
                  int l = (Integer)s.getValue(SnowLayerBlock.LAYERS);
                  return l >= 8 ? y : y - 1 + l * 0.125;
               } else if (b instanceof SkullBlock) {
                  return y - 1 + 0.5;
               } else if (b == Blocks.FARMLAND || b == Blocks.DIRT_PATH || b == Blocks.HONEY_BLOCK) {
                  return y - 0.0625;
               } else if (b == Blocks.SOUL_SAND) {
                  return y - 0.125;
               } else if (b == Blocks.CACTUS) {
                  return Double.NaN;
               } else if (s.isCollisionShapeFullBlock(this.level, this.p(x, y - 1, z))) {
                  return y;
               } else {
                  VoxelShape shape = s.getCollisionShape(this.level, this.p(x, y - 1, z));
                  if (shape.isEmpty()) {
                     return Double.NaN;
                  } else {
                     double h = shape.max(Axis.Y);
                     return h <= 1.0 ? y - 1 + h : Double.NaN;
                  }
               }
            }
         }
      }
   }

   public boolean magma(int x, int y, int z) {
      return this.st(x, y, z).getBlock() == Blocks.MAGMA_BLOCK;
   }

   public boolean honey(int x, int y, int z) {
      return this.st(x, y, z).getBlock() == Blocks.HONEY_BLOCK;
   }

   public boolean ice(int x, int y, int z) {
      return this.st(x, y - 1, z).is(BlockTags.ICE);
   }

   public boolean soulSand(int x, int y, int z) {
      return this.st(x, y, z).getBlock() == Blocks.SOUL_SAND;
   }

   public boolean soulBlock(int x, int y, int z) {
      return this.st(x, y, z).is(BlockTags.SOUL_SPEED_BLOCKS);
   }

   public boolean dripstone(int x, int y, int z) {
      return this.st(x, y, z).getBlock() == Blocks.POINTED_DRIPSTONE || this.st(x, y - 1, z).getBlock() == Blocks.POINTED_DRIPSTONE;
   }

   public boolean magmaSafe() {
      return this.phys.fireResist || this.phys.frostWalker > 0;
   }

   public boolean safeLanding(int x, int y, int z) {
      if (!this.water(x, y, z) && !this.water(x, y - 1, z)) {
         Block b = this.st(x, y - 1, z).getBlock();
         if (b != Blocks.SLIME_BLOCK && b != Blocks.POWDER_SNOW && b != Blocks.COBWEB) {
            Block c = this.st(x, y, z).getBlock();
            return c != Blocks.COBWEB && c != Blocks.POWDER_SNOW && c != Blocks.SWEET_BERRY_BUSH
               ? this.st(x, y, z).is(BlockTags.FALL_DAMAGE_RESETTING) || this.st(x, y - 1, z).is(BlockTags.FALL_DAMAGE_RESETTING)
               : true;
         } else {
            return true;
         }
      } else {
         return true;
      }
   }

   public boolean hayHoney(int x, int y, int z) {
      Block b = this.st(x, y - 1, z).getBlock();
      return b == Blocks.HAY_BLOCK || b == Blocks.HONEY_BLOCK;
   }

   public double landingMult(int x, int y, int z) {
      return PlayerPhysics.landingMult(this.safeLanding(x, y, z), this.hayHoney(x, y, z), this.dripstone(x, y, z));
   }

   public int kind(int x, int y, int z) {
      this.maybeInvalidate();
      long k = BlockPos.asLong(x, y, z);
      int i = kIdx(k);
      if (this.kKeys[i] == k) {
         return this.kKinds[i];
      } else {
         int kind = this.computeKind(x, y, z);
         double floor = kind == 1 ? this.supportFloor(x, y, z) : (kind == 6 ? y + 0.875 : y);
         this.kKeys[i] = k;
         this.kKinds[i] = kind;
         this.kFloors[i] = floor;
         return kind;
      }
   }

   private int computeKind(int x, int y, int z) {
      if (!this.loaded(x, y, z)) {
         return 0;
      } else if (this.tempBlocked.contains(BlockPos.asLong(x, y, z))) {
         return 0;
      } else if ((!this.phys.flying || !this.cfg.flyAuto) && !this.phys.spectator) {
         if (this.water(x, y, z)) {
            if (this.phys.sbWaterWalk && !this.water(x, y + 1, z) && this.passable(x, y + 1, z) && this.passable(x, y + 2, z)) {
               return 6;
            } else {
               return this.cfg.swim && this.passable(x, y + 1, z) ? 2 : 0;
            }
         } else if (this.lava(x, y, z)) {
            return this.phys.fireResist && this.cfg.swim && this.passable(x, y + 1, z) ? 3 : 0;
         } else if (this.climb(x, y, z)) {
            return this.cfg.climb && this.passable(x, y + 1, z) ? 4 : 0;
         } else if (this.passable(x, y, z) && this.passable(x, y + 1, z)) {
            double f = this.supportFloor(x, y, z);
            if (Double.isNaN(f)) {
               return 0;
            } else if (f > y + 0.001 && !this.passable(x, y + 2, z)) {
               return 0;
            } else if (this.cfg.avoidDanger && !this.cfg.sneakMagma && !this.magmaSafe() && this.magma(x, y - 1, z)) {
               return 0;
            } else {
               return this.cfg.avoidDanger && this.dripstone(x, y, z) ? 0 : 1;
            }
         } else {
            return 0;
         }
      } else {
         return this.passable(x, y, z) ? 5 : 0;
      }
   }

   public double floorOf(int kind, int x, int y, int z) {
      if (kind == 6) {
         return y + 0.875;
      } else if (kind != 1) {
         return y;
      } else {
         this.maybeInvalidate();
         long k = BlockPos.asLong(x, y, z);
         int i = kIdx(k);
         return this.kKeys[i] == k && this.kKinds[i] == 1 ? this.kFloors[i] : this.supportFloor(x, y, z);
      }
   }

   public double cellExtra(int x, int y, int z) {
      BlockState s = this.st(x, y, z);
      Block b = s.getBlock();
      double extra = 0.0;
      if (b == Blocks.SWEET_BERRY_BUSH) {
         extra += this.cfg.avoidDanger ? 30.0 : 10.0;
      }

      if (b == Blocks.COBWEB) {
         extra += 25.0;
      }

      if (b instanceof SnowLayerBlock) {
         extra += ((Integer)s.getValue(SnowLayerBlock.LAYERS)).intValue() * 1.2;
      }

      if (b instanceof WallSkullBlock) {
         extra++;
      }

      if (this.doorClosed(x, y, z)) {
         extra += 8.0;
      }

      if (this.water(x, y, z) && this.water(x, y + 1, z)) {
         extra += 2.0;
      }

      if (this.lava(x, y, z)) {
         extra += 8.0;
      }

      return extra;
   }

   public double surfaceFactor(int x, int y, int z) {
      BlockState below = this.st(x, y - 1, z);
      Block b = below.getBlock();
      if (below.is(BlockTags.SOUL_SPEED_BLOCKS)) {
         if (this.phys.soulSpeed > 0) {
            return 1.0 / (1.0 + 0.105 * this.phys.soulSpeed);
         } else {
            return b == Blocks.SOUL_SAND ? 2.0 : 1.0;
         }
      } else if (b == Blocks.HONEY_BLOCK) {
         return 2.4;
      } else if (below.is(BlockTags.ICE)) {
         return 0.9;
      } else {
         return this.magma(x, y - 1, z) && this.cfg.sneakMagma && !this.magmaSafe() ? 1.0 / this.phys.sneakFactor : 1.0;
      }
   }

   public double fallTime(double d) {
      return this.phys.slowFalling ? d / 0.15 + 6.0 : 2.2 * Math.sqrt(Math.max(0.0, d)) + 3.0;
   }

   public double maxDrop() {
      if (!this.cfg.descend) {
         return 0.0;
      } else {
         double free = Math.max((double)this.cfg.maxFall, this.phys.safeFall);
         double v = this.cfg.riskyFalls ? Math.max(free, this.phys.maxSafeDrop(this.cfg.keepHearts)) : free;
         return Math.min(v, (double)this.cfg.maxFallScan);
      }
   }
}
