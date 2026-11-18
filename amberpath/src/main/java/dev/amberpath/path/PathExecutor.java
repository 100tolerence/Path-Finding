package dev.amberpath.path;

import dev.amberpath.config.AmberConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class PathExecutor {
   private final Minecraft mc;
   private final AmberConfig cfg;
   private final WorldModel model;
   private final PathEngine.PathResult path;
   public int idx = 1;
   public boolean stuckByCollision = false;
   public final AimController aim = new AimController();
   private int stuckTicks = 0;
   private int collisionTicks = 0;
   private int doorCooldown = 0;
   private int validateTimer = 0;
   private int moveTicks = 0;
   private int devTicks = 0;
   private double bestDist = Double.MAX_VALUE;
   private boolean wasAirborne = false;
   private boolean djDone = false;
   private int djHold = 0;
   private boolean kF;
   private boolean kJ;
   private boolean kSn;
   private boolean kSp;

   public PathExecutor(Minecraft mc, AmberConfig cfg, WorldModel model, PathEngine.PathResult path) {
      this.mc = mc;
      this.cfg = cfg;
      this.model = model;
      this.path = path;
   }

   public BlockPos target() {
      return this.idx < this.path.nodes.size() ? this.path.nodes.get(this.idx) : null;
   }

   public boolean currentWaterRun() {
      return this.idx - 1 >= 0 && this.idx - 1 < this.path.moves.size() && this.path.moves.get(this.idx - 1).waterRun;
   }

   public boolean manualInput() {
      Options o = this.mc.options;
      return o.keyUp.isDown() != this.kF
         || o.keyDown.isDown()
         || o.keyLeft.isDown()
         || o.keyRight.isDown()
         || o.keyJump.isDown() != this.kJ
         || o.keyShift.isDown() != this.kSn
         || o.keySprint.isDown() != this.kSp;
   }

   public void release() {
      this.aim.clear();
      Options o = this.mc.options;
      o.keyUp.setDown(false);
      o.keyDown.setDown(false);
      o.keyLeft.setDown(false);
      o.keyRight.setDown(false);
      o.keyJump.setDown(false);
      o.keyShift.setDown(false);
      o.keySprint.setDown(false);
      this.kF = this.kJ = this.kSn = this.kSp = false;
      this.djHold = 0;
   }

   private double speed(LocalPlayer p) {
      return p.getDeltaMovement().horizontalDistance();
   }

   private void advanceReset() {
      this.stuckTicks = 0;
      this.bestDist = Double.MAX_VALUE;
      this.moveTicks = 0;
      this.djDone = false;
      this.djHold = 0;
      this.devTicks = 0;
   }

   private void skipAhead(LocalPlayer p) {
      Vec3 f = p.position();
      double v = this.speed(p);
      double latTol = 0.7 + v * 0.35;
      double reach = Math.max(0.5, v * 0.95);
      double vertTol = Math.max(0.9, v * 0.6 + 0.4);
      int j = this.idx;
      int limit = Math.min(this.path.nodes.size(), this.idx + 12);

      while (j < limit) {
         BlockPos n = this.path.nodes.get(j);
         int k = this.model.kind(n.getX(), n.getY(), n.getZ());
         double tf = k != 1 && k != 6 ? n.getY() : this.model.floorOf(k, n.getX(), n.getY(), n.getZ());
         if (Double.isNaN(tf)) {
            tf = n.getY();
         }

         double dx = n.getX() + 0.5 - f.x;
         double dz = n.getZ() + 0.5 - f.z;
         double d2 = Math.hypot(dx, dz);
         boolean near = k != 1 && k != 6 ? Math.sqrt(d2 * d2 + (tf - f.y) * (tf - f.y)) < Math.max(0.65, v * 0.9) : d2 < reach && Math.abs(f.y - tf) < vertTol;
         if (near) {
            j++;
         } else {
            if (j < 1) {
               break;
            }

            BlockPos prev = this.path.nodes.get(j - 1);
            double dirx = n.getX() - prev.getX();
            double dirz = n.getZ() - prev.getZ();
            double len = Math.hypot(dirx, dirz);
            if (!(len > 0.0)) {
               break;
            }

            dirx /= len;
            dirz /= len;
            double rx = f.x - (n.getX() + 0.5);
            double rz = f.z - (n.getZ() + 0.5);
            double along = rx * dirx + rz * dirz;
            double lat = Math.abs(rx * -dirz + rz * dirx);
            if (!(along > 0.1) || !(lat < latTol) || !(Math.abs(f.y - tf) < vertTol + 0.6)) {
               break;
            }

            j++;
         }
      }

      if (j > this.idx) {
         this.idx = j;
         this.advanceReset();
      }
   }

   private int recoverAfterFall(Vec3 feet) {
      int limit = Math.min(this.path.nodes.size(), this.idx + 64);

      for (int j = this.idx; j < limit; j++) {
         BlockPos n = this.path.nodes.get(j);
         int k = this.model.kind(n.getX(), n.getY(), n.getZ());
         double tf = k != 1 && k != 6 ? n.getY() : this.model.floorOf(k, n.getX(), n.getY(), n.getZ());
         if (Double.isNaN(tf)) {
            tf = n.getY();
         }

         double d = Math.hypot(n.getX() + 0.5 - feet.x, n.getZ() + 0.5 - feet.z);
         if (d < 2.3 && Math.abs(feet.y - tf) < 2.8) {
            return j;
         }
      }

      return -1;
   }

   private boolean jumpStillValid(BlockPos t, double tf, PathEngine.Move mv) {
      int k = this.model.kind(t.getX(), t.getY(), t.getZ());
      if (k != 1 && k != 6) {
         return false;
      } else {
         double f = this.model.floorOf(k, t.getX(), t.getY(), t.getZ());
         if (!Double.isNaN(f) && !(Math.abs(f - tf) > 0.3)) {
            if (mv.type == 3 && mv.gap >= 2 && this.idx - 1 >= 0) {
               BlockPos s = this.path.nodes.get(this.idx - 1);
               int sx = Integer.signum(t.getX() - s.getX());
               int sz = Integer.signum(t.getZ() - s.getZ());

               for (int c = 1; c < mv.gap; c++) {
                  int cx = s.getX() + sx * c;
                  int cz = s.getZ() + sz * c;
                  if (!this.model.passable(cx, s.getY() + 1, cz) || !this.model.passable(cx, s.getY() + 2, cz)) {
                     return false;
                  }
               }
            }

            return true;
         } else {
            return false;
         }
      }
   }

   private boolean farFromPath(LocalPlayer p) {
      Vec3 f = p.position();
      int lo = Math.max(0, this.idx - 1);
      int hi = Math.min(this.path.nodes.size() - 1, this.idx + 9);
      double best = Double.MAX_VALUE;

      for (int j = lo; j < hi; j++) {
         BlockPos a = this.path.nodes.get(j);
         BlockPos b = this.path.nodes.get(j + 1);
         double ax = a.getX() + 0.5;
         double az = a.getZ() + 0.5;
         double vx = b.getX() - ax;
         double vz = b.getZ() - az;
         double len2 = vx * vx + vz * vz;
         double t = len2 > 1.0E-9 ? ((f.x - ax) * vx + (f.z - az) * vz) / len2 : 0.0;
         t = Math.max(0.0, Math.min(1.0, t));
         double d = Math.hypot(f.x - (ax + vx * t), f.z - (az + vz * t));
         if (d < best) {
            best = d;
         }
      }

      if (best == Double.MAX_VALUE) {
         BlockPos n = this.path.nodes.get(lo);
         best = Math.hypot(n.getX() + 0.5 - f.x, n.getZ() + 0.5 - f.z);
      }

      return best > 2.6;
   }

   public PathExecutor.Signal tick() {
      LocalPlayer p = this.mc.player;
      if (p == null) {
         return PathExecutor.Signal.NONE;
      } else if (this.idx >= this.path.nodes.size()) {
         this.release();
         return PathExecutor.Signal.PATH_END;
      } else {
         this.skipAhead(p);
         if (this.idx >= this.path.nodes.size()) {
            this.release();
            return PathExecutor.Signal.PATH_END;
         } else {
            this.moveTicks++;
            if (++this.validateTimer >= 4) {
               this.validateTimer = 0;

               for (int j = this.idx; j < Math.min(this.idx + 6, this.path.nodes.size()); j++) {
                  BlockPos n = this.path.nodes.get(j);
                  if (this.model.kind(n.getX(), n.getY(), n.getZ()) == 0) {
                     this.release();
                     return PathExecutor.Signal.BLOCKED;
                  }
               }
            }

            BlockPos t = this.path.nodes.get(this.idx);
            PathEngine.Move mv = this.path.moves.get(this.idx - 1);
            int tk = this.model.kind(t.getX(), t.getY(), t.getZ());
            if (tk == 0) {
               tk = 1;
            }

            if (tk == 3 && !p.hasEffect(MobEffects.FIRE_RESISTANCE)) {
               this.release();
               return PathExecutor.Signal.BLOCKED;
            } else {
               double tf = this.model.floorOf(tk, t.getX(), t.getY(), t.getZ());
               if (Double.isNaN(tf)) {
                  tf = t.getY();
               }

               Vec3 feet = p.position();
               double v = this.speed(p);
               double tx = t.getX() + 0.5;
               double tz = t.getZ() + 0.5;
               double dx = tx - feet.x;
               double dz = tz - feet.z;
               double dist2 = Math.hypot(dx, dz);
               double dy = tf - feet.y;
               double dist3 = Math.sqrt(dx * dx + dy * dy + dz * dz);
               boolean advanced = tk != 1 && tk != 6
                  ? dist3 < Math.max(0.65, v * 0.85)
                  : dist2 < Math.max(0.42, v * 0.85) && Math.abs(feet.y - tf) < Math.max(0.75, v * 0.5 + 0.3);
               if (advanced) {
                  this.idx++;
                  this.advanceReset();
                  return PathExecutor.Signal.NONE;
               } else {
                  if (p.onGround() && this.farFromPath(p)) {
                     if (++this.devTicks >= (v > 1.0 ? 7 : 12)) {
                        this.release();
                        return PathExecutor.Signal.DEVIATED;
                     }
                  } else {
                     this.devTicks = 0;
                  }

                  boolean onGround = p.onGround();
                  boolean inWater = p.isInWater();
                  if (mv.type == 3 && !onGround && p.getDeltaMovement().y < 0.0 && feet.y < tf - 0.35 && dist2 > 1.0) {
                     this.release();
                     return PathExecutor.Signal.JUMP_FAILED;
                  } else if ((mv.type == 3 || mv.type == 1) && onGround && this.moveTicks > 30) {
                     this.release();
                     return PathExecutor.Signal.STUCK;
                  } else {
                     if (!onGround && p.getDeltaMovement().y < -0.02) {
                        if (feet.y < this.mc.level.getMinY() + 1) {
                           this.release();
                           return PathExecutor.Signal.STUCK;
                        }

                        if (feet.y < tf - 3.0) {
                           int rec = this.recoverAfterFall(feet);
                           if (rec > 0) {
                              this.idx = rec;
                              this.advanceReset();
                              this.release();
                              return PathExecutor.Signal.NONE;
                           }

                           if (feet.y < tf - 6.0) {
                              this.release();
                              return PathExecutor.Signal.STUCK;
                           }
                        }
                     }

                     if (this.wasAirborne && onGround) {
                        int recx = this.recoverAfterFall(feet);
                        if (recx > this.idx) {
                           this.idx = recx;
                        }

                        this.advanceReset();
                     }

                     this.wasAirborne = !onGround;
                     if (mv.door && this.model.doorClosed(t.getX(), t.getY(), t.getZ()) && dist3 <= 2.6) {
                        this.aim.setPrecise(true);
                        this.aim(p, tx, t.getY() + 0.5, tz, true);
                        if (--this.doorCooldown <= 0) {
                           this.doorCooldown = 8;
                           Vec3 hit = new Vec3(tx, t.getY() + 0.5, tz);
                           this.mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, t, false));
                        }

                        this.setKeys(false, false, false, false);
                        return PathExecutor.Signal.NONE;
                     } else {
                        double aimX = tx;
                        double aimZ = tz;
                        if (mv.type == 0 && !mv.door && !mv.waterRun && this.idx + 1 < this.path.nodes.size()) {
                           PathEngine.Move mv2 = this.path.moves.get(this.idx);
                           if (mv2.type == 0 && !mv2.door) {
                              BlockPos n2 = this.path.nodes.get(this.idx + 1);
                              double nx2 = n2.getX() + 0.5;
                              double nz2 = n2.getZ() + 0.5;
                              double d2x = nx2 - feet.x;
                              double d2z = nz2 - feet.z;
                              double l2 = Math.hypot(d2x, d2z);
                              if (dist2 > 0.001 && l2 > 0.001) {
                                 double dot = (dx * d2x + dz * d2z) / (dist2 * l2);
                                 if (dot > 0.55 && dist2 < Math.max(1.6, v * 2.6)) {
                                    aimX = tx + (nx2 - tx) * 0.45;
                                    aimZ = tz + (nz2 - tz) * 0.45;
                                 }
                              }
                           }
                        }

                        float desiredYaw = (float)Math.toDegrees(Math.atan2(-(aimX - feet.x), aimZ - feet.z));
                        boolean verticalAim = tk == 2 || tk == 3 || tk == 4 || tk == 5 || mv.type == 5;
                        float desiredPitch = verticalAim
                           ? Mth.clamp((float)(-Math.toDegrees(Math.atan2(dy + (tk == 4 ? 0.4 : 0.0), Math.max(0.3, dist2)))), -80.0F, 80.0F)
                           : (mv.type == 6 ? 70.0F : Mth.clamp((float)(-Math.toDegrees(Math.atan2(dy, Math.max(0.3, dist2)))), -20.0F, 35.0F));
                        this.aim.setPrecise(mv.type == 3 || mv.door);
                        this.aim(p, desiredYaw, desiredPitch, true, v);
                        float err = Math.abs(Mth.wrapDegrees(desiredYaw - p.getYRot()));
                        boolean aligned = err < (mv.type == 3 ? 16 : 50);
                        boolean forward = aligned;
                        boolean jump = false;
                        boolean sneak = false;
                        boolean sprint = false;
                        if (mv.waterRun) {
                           sprint = aligned;
                           forward = aligned || v > 0.2 && err < 75.0F;
                           jump = p.isUnderWater() || feet.y < tf - 0.3;
                           if (inWater && v < 0.12) {
                              jump = true;
                           }
                        } else {
                           switch (mv.type) {
                              case 1:
                                 sprint = aligned && this.cfg.sprint && mv.sprint;
                                 if (onGround && aligned && dist2 < 1.2 + v && dist2 > 0.2) {
                                    if (!this.jumpStillValid(t, tf, mv)) {
                                       this.release();
                                       return PathExecutor.Signal.BLOCKED;
                                    }

                                    jump = true;
                                 }
                                 break;
                              case 2:
                                 if (mv.drop > 0.0) {
                                    double dmg = this.model.phys.fallDamage(mv.drop) * this.model.landingMult(t.getX(), t.getY(), t.getZ());
                                    double budget = p.getHealth() + p.getAbsorptionAmount() - 2.0 * this.cfg.keepHearts;
                                    if (dmg > 0.0 && dmg >= budget) {
                                       this.release();
                                       return PathExecutor.Signal.FALL_UNSAFE;
                                    }
                                 }

                                 sprint = aligned && this.cfg.sprint;
                                 forward = aligned;
                                 if (!onGround) {
                                    forward = dist2 >= Math.max(0.5, v * 1.4) && aligned;
                                 }
                                 break;
                              case 3:
                                 if (onGround && tf < feet.y - 0.4) {
                                    double dmg = this.model.phys.fallDamage(feet.y - tf) * this.model.landingMult(t.getX(), t.getY(), t.getZ());
                                    double budget = p.getHealth() + p.getAbsorptionAmount() - 2.0 * this.cfg.keepHearts;
                                    if (dmg > 0.0 && dmg >= budget) {
                                       this.release();
                                       return PathExecutor.Signal.FALL_UNSAFE;
                                    }
                                 }

                                 sprint = aligned && this.cfg.sprint;
                                 forward = aligned;
                                 if (onGround && aligned && p.isSprinting() && dist2 <= mv.gap + 0.6 + v * 1.2 && dist2 >= mv.gap - 0.6 - v * 0.5) {
                                    if (!this.jumpStillValid(t, tf, mv)) {
                                       this.release();
                                       return PathExecutor.Signal.BLOCKED;
                                    }

                                    jump = true;
                                 }

                                 if (!onGround) {
                                    forward = true;
                                    sprint = this.cfg.sprint;
                                    if (mv.doubleJump && !this.djDone && p.getDeltaMovement().y <= 0.0) {
                                       this.djDone = true;
                                       this.djHold = 2;
                                    }

                                    if (this.djHold > 0) {
                                       jump = true;
                                       this.djHold--;
                                    }
                                 }
                                 break;
                              case 4:
                                 forward = aligned || dist2 < 0.4;
                                 if (dy > 0.25 || inWater && !p.isUnderWater() && dy > -0.2) {
                                    jump = true;
                                 }

                                 sprint = this.cfg.sprint && aligned && p.isUnderWater();
                                 break;
                              case 5:
                                 forward = aligned;
                                 if (this.model.scaff(t.getX(), t.getY(), t.getZ()) || this.model.scaff(p.getBlockX(), p.getBlockY(), p.getBlockZ())) {
                                    jump = true;
                                 }
                                 break;
                              case 6:
                                 forward = aligned;
                                 sneak = false;
                                 break;
                              case 7:
                                 forward = false;
                                 sneak = true;
                                 break;
                              case 8:
                                 forward = aligned;
                                 if (dy > 0.3) {
                                    jump = true;
                                 }

                                 if (dy < -0.3) {
                                    sneak = true;
                                 }

                                 sprint = this.cfg.sprint && dist3 > 3.0;
                                 break;
                              default:
                                 forward = aligned;
                                 sprint = aligned && this.cfg.sprint && mv.sprint && dist2 > 1.6;
                                 if (dy > 0.55 && dy <= 1.05 && onGround && aligned && dist2 < 1.2 + v) {
                                    jump = true;
                                 }
                           }
                        }

                        if (this.cfg.sneakMagma
                           && !this.model.magmaSafe()
                           && (this.model.magma(p.getBlockX(), p.getBlockY() - 1, p.getBlockZ()) || this.model.magma(t.getX(), t.getY() - 1, t.getZ()))) {
                           sneak = true;
                           sprint = false;
                        }

                        if (inWater && !mv.waterRun && mv.type != 4 && tk == 1) {
                           jump = jump || aligned;
                        }

                        this.setKeys(forward, jump, sneak, sprint);
                        double d = tk != 1 && tk != 6 ? dist3 : dist2;
                        if (d < this.bestDist - 0.04) {
                           this.bestDist = d;
                           this.stuckTicks = 0;
                        } else {
                           this.stuckTicks++;
                        }

                        if (p.horizontalCollision && forward) {
                           this.collisionTicks++;
                        } else {
                           this.collisionTicks = 0;
                        }

                        this.stuckByCollision = this.collisionTicks > 6;
                        if (this.collisionTicks > 8) {
                           this.release();
                           return PathExecutor.Signal.BLOCKED;
                        } else if (this.stuckTicks > 20) {
                           this.release();
                           return PathExecutor.Signal.STUCK;
                        } else {
                           return PathExecutor.Signal.NONE;
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void aim(LocalPlayer p, double tx, double ty, double tz, boolean smooth) {
      float yaw = (float)Math.toDegrees(Math.atan2(-(tx - p.getX()), tz - p.getZ()));
      float pitch = Mth.clamp(
         (float)(-Math.toDegrees(Math.atan2(ty - (p.getY() + p.getEyeHeight()), Math.max(0.3, Math.hypot(tx - p.getX(), tz - p.getZ()))))), -80.0F, 80.0F
      );
      this.aim(p, yaw, pitch, smooth, this.speed(p));
   }

   private void aim(LocalPlayer p, float yaw, float pitch, boolean smooth, double v) {
      this.aim.setTarget(yaw, pitch, true, v, 1.0);
      this.aim.tickFallback(p);
   }

   private void setKeys(boolean forward, boolean jump, boolean sneak, boolean sprint) {
      Options o = this.mc.options;
      o.keyUp.setDown(forward);
      o.keyDown.setDown(false);
      o.keyLeft.setDown(false);
      o.keyRight.setDown(false);
      o.keyJump.setDown(jump);
      o.keyShift.setDown(sneak);
      o.keySprint.setDown(sprint);
      this.kF = forward;
      this.kJ = jump;
      this.kSn = sneak;
      this.kSp = sprint;
   }

   public double remainingDistance() {
      if (this.mc.player != null && this.idx < this.path.nodes.size()) {
         double d = this.mc.player.position().distanceTo(Vec3.atCenterOf((Vec3i)this.path.nodes.get(this.idx)));

         for (int i = this.idx; i < this.path.nodes.size() - 1; i++) {
            d += Vec3.atCenterOf((Vec3i)this.path.nodes.get(i)).distanceTo(Vec3.atCenterOf((Vec3i)this.path.nodes.get(i + 1)));
         }

         return d;
      } else {
         return 0.0;
      }
   }

   public double remainingTicks() {
      double t = 0.0;

      for (int i = Math.max(0, this.idx - 1); i < this.path.moves.size(); i++) {
         t += this.path.moves.get(i).cost;
      }

      return t;
   }

   public static enum Signal {
      NONE,
      PATH_END,
      STUCK,
      BLOCKED,
      FALL_UNSAFE,
      JUMP_FAILED,
      DEVIATED;
   }
}
