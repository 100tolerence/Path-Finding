package dev.amberpath.path;

import dev.amberpath.config.AmberConfig;
import dev.amberpath.skyblock.SkyblockHelper;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.Gizmos.TemporaryCollection;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

public class Navigator {
   private final Minecraft mc = Minecraft.getInstance();
   public final AmberConfig cfg;
   public Navigator.Status status = Navigator.Status.IDLE;
   public PathEngine.Goal goal;
   public String pauseReason = "";
   public long arrivedAt = 0L;
   public long failedAt = 0L;
   public int lastExpansions = 0;
   public long lastComputeMs = 0L;
   public double lastPathLen = 0.0;
   public double lastSpeedFactor = 1.0;
   public int lastMaxGap = 3;
   public int lastSmoothed = 0;
   private PathEngine engine;
   private PathExecutor executor;
   private PathEngine.PathResult path;
   private PathEngine.PathResult queuedPath;
   private WorldModel model;
   private final Map<Long, Long> tempBlocked = new HashMap<>();
   private float lastHealth = 20.0F;
   private int damageWindow = 0;
   private int replans = 0;
   private int refreshTimer = 0;
   private int sbTimer = 0;
   private long retryAt = 0L;
   private int activationFails = 0;
   private Vec3 prevTickPos;
   private int deepValidateTimer = 0;
   private boolean prevCanSprint;
   private boolean prevCanDoubleJump;
   private boolean prevWaterWalk;
   private boolean physDegraded;
   private double prevEffSpeed = 1.0;
   private double bestDistToGoal = Double.MAX_VALUE;
   private double bestRemaining = Double.MAX_VALUE;
   private long bestProgressAt = 0L;
   private static final long PROGRESS_TIMEOUT_MS = 45000L;
   private String lastMsg = "";
   private long lastMsgAt = 0L;
   private String pendingStop = null;
   private int stopGrace = 0;
   private int screenGrace = 0;
   private float snapYaw;
   private float snapPitch;
   private boolean snapValid = false;

   public Navigator(AmberConfig cfg) {
      this.cfg = cfg;
   }

   public boolean active() {
      return this.status == Navigator.Status.COMPUTING || this.status == Navigator.Status.RUNNING || this.status == Navigator.Status.PAUSED;
   }

   public PlayerPhysics physics() {
      return this.model != null ? this.model.phys : null;
   }

   public void frameAim() {
      if (this.status == Navigator.Status.RUNNING && this.executor != null) {
         this.executor.aim.frame(this.mc.player);
      }
   }

   public boolean onWaterRun() {
      return this.executor != null && this.executor.currentWaterRun();
   }

   public void msg(String text) {
      long now = System.currentTimeMillis();
      if (!text.equals(this.lastMsg) || now - this.lastMsgAt >= 2000L) {
         this.lastMsg = text;
         this.lastMsgAt = now;
         if (this.mc.player != null) {
            this.mc.player.sendSystemMessage(Component.literal("§6[AmberPath] §r" + text));
         }
      }
   }

   public void start(PathEngine.Goal g) {
      if (this.mc.player != null && this.mc.level != null) {
         this.cfg.enforceNavigationPolicy();
         this.goal = g;
         this.replans = 0;
         this.retryAt = 0L;
         this.pendingStop = null;
         this.activationFails = 0;
         this.lastHealth = this.mc.player.getHealth();
         this.prevTickPos = this.mc.player.position();
         this.bestDistToGoal = Double.MAX_VALUE;
         this.bestRemaining = Double.MAX_VALUE;
         this.bestProgressAt = System.currentTimeMillis();
         this.physDegraded = false;
         this.deepValidateTimer = 0;
         this.snapshotPhys();
         if (this.executor != null) {
            this.executor.release();
         }

         this.engine = null;
         this.queuedPath = null;
         this.executor = null;
         this.status = Navigator.Status.COMPUTING;
         this.screenGrace = 3;
         this.snapshotRot();
         this.mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7F, 1.4F);
         this.msg("Navigation vers §e" + g.x + ", " + (g.y == null ? "auto" : g.y) + ", " + g.z + "§r… (§7/go stop§r pour arrêter)");
      }
   }

   public void stop() {
      if (this.executor != null) {
         this.executor.release();
      }

      this.status = Navigator.Status.IDLE;
      this.engine = null;
      this.executor = null;
      this.path = null;
      this.queuedPath = null;
      this.pendingStop = null;
      this.snapValid = false;
   }

   private void requestStop(String reason) {
      if (this.pendingStop == null && this.active()) {
         LocalPlayer p = this.mc.player;
         if (p != null && this.executor != null && !p.onGround() && !p.isInWater()) {
            this.pendingStop = reason;
            this.stopGrace = 60;
         } else {
            this.stop();
            this.msg("Navigation §carrêtée§r — " + reason + ".");
         }
      }
   }

   private void snapshotRot() {
      LocalPlayer p = this.mc.player;
      if (p != null) {
         this.snapYaw = p.getYRot();
         this.snapPitch = p.getXRot();
         this.snapValid = true;
      }
   }

   private void snapshotPhys() {
      PlayerPhysics ph = this.physics();
      if (ph != null) {
         this.prevCanSprint = ph.canSprint;
         this.prevCanDoubleJump = ph.canDoubleJump;
         this.prevWaterWalk = ph.sbWaterWalk;
         this.prevEffSpeed = ph.effSpeed;
      }
   }

   private static double hSpeed(LocalPlayer p) {
      return p.getDeltaMovement().horizontalDistance();
   }

   private boolean manualKeys() {
      Options o = this.mc.options;
      return o.keyUp.isDown()
         || o.keyDown.isDown()
         || o.keyLeft.isDown()
         || o.keyRight.isDown()
         || o.keyJump.isDown()
         || o.keyShift.isDown()
         || o.keySprint.isDown();
   }

   private boolean manualLook() {
      LocalPlayer p = this.mc.player;
      if (p == null) {
         return false;
      } else if (this.executor != null) {
         return this.executor.aim.externalLook(p);
      } else {
         return !this.snapValid ? false : Math.abs(Mth.wrapDegrees(p.getYRot() - this.snapYaw)) > 1.5F || Math.abs(p.getXRot() - this.snapPitch) > 1.5F;
      }
   }

   private BlockPos findStart() {
      LocalPlayer p = this.mc.player;
      int x = Mth_floor(p.getX());
      int z = Mth_floor(p.getZ());
      int y0 = Mth_floor(p.getY());

      for (int y = y0 + 2; y >= y0 - 3; y--) {
         if (this.model.kind(x, y, z) != 0) {
            return new BlockPos(x, y, z);
         }
      }

      return !p.onGround() && !p.isInWater() ? null : new BlockPos(x, y0, z);
   }

   private static int Mth_floor(double v) {
      return (int)Math.floor(v);
   }

   private boolean beginSearch() {
      this.model = new WorldModel(this.mc.level, this.mc.player, this.cfg, this.tempBlocked.keySet());
      this.lastSpeedFactor = this.model.phys.effSpeed;
      this.lastMaxGap = this.cfg.adaptiveParkour ? Math.max(this.cfg.maxGap, this.model.phys.maxGapFlat) : this.cfg.maxGap;
      BlockPos s = this.findStart();
      if (s == null) {
         this.engine = null;
         return false;
      } else {
         this.engine = new PathEngine(this.model, this.cfg, s, this.goal);
         return true;
      }
   }

   private boolean beginLookahead(BlockPos from) {
      if (from != null && this.model != null && this.goal != null) {
         this.engine = new PathEngine(this.model, this.cfg, from, this.goal);
         this.queuedPath = null;
         return true;
      } else {
         return false;
      }
   }

   private boolean usable(PathEngine.PathResult candidate) {
      return candidate != null && candidate.nodes != null && candidate.nodes.size() >= 2;
   }

   private boolean activatePath(PathEngine.PathResult candidate) {
      if (!this.usable(candidate)) {
         return false;
      } else {
         int lim = Math.min(candidate.nodes.size(), 5);

         for (int i = 1; i < lim; i++) {
            BlockPos n = candidate.nodes.get(i);
            if (this.model.kind(n.getX(), n.getY(), n.getZ()) == 0) {
               return false;
            }
         }

         this.path = candidate;
         this.lastPathLen = this.path.cost;
         this.lastSmoothed = this.path.smoothed;
         this.executor = new PathExecutor(this.mc, this.cfg, this.model, this.path);
         this.status = Navigator.Status.RUNNING;
         this.activationFails = 0;
         this.bestRemaining = Double.MAX_VALUE;
         this.bestProgressAt = System.currentTimeMillis();
         if (this.path.partial) {
            this.beginLookahead(this.path.nodes.get(this.path.nodes.size() - 1));
         }

         return true;
      }
   }

   private double sweptDist(Vec3 pos, Vec3 from, double cx, double cz) {
      double vx = pos.x - from.x;
      double vz = pos.z - from.z;
      double len2 = vx * vx + vz * vz;
      double t = len2 > 1.0E-9 ? ((cx - from.x) * vx + (cz - from.z) * vz) / len2 : 0.0;
      t = Mth.clamp(t, 0.0, 1.0);
      return Math.hypot(cx - (from.x + vx * t), cz - (from.z + vz * t));
   }

   private boolean goalReached(Vec3 f, Vec3 from) {
      LocalPlayer p = this.mc.player;
      if (this.goal != null && p != null) {
         switch (this.goal.mode) {
            case 0:
               boolean grounded = p.onGround() || p.isInWater();
               boolean yOk = this.goal.y == null || Math.abs(f.y - (this.goal.y + 1)) < 0.8;
               if (grounded && yOk) {
                  double rad = 0.75 + Math.min(hSpeed(p), 2.5) * 0.3;
                  double dx0 = f.x - (this.goal.x + 0.5);
                  double dz0 = f.z - (this.goal.z + 0.5);
                  if (dx0 * dx0 + dz0 * dz0 < rad * rad) {
                     return true;
                  }

                  return this.sweptDist(f, from, this.goal.x + 0.5, this.goal.z + 0.5) < 0.85;
               }

               return false;
            case 1:
               return Mth_floor(f.x) == this.goal.x && Mth_floor(f.z) == this.goal.z && (this.goal.y == null || Math.abs(f.y - this.goal.y.intValue()) < 0.8);
            default:
               double rad = this.goal.radius + 0.5;
               boolean groundedR = p.onGround() || p.isInWater();
               boolean yOkR = this.goal.y == null || Math.abs(f.y - this.goal.y.intValue()) <= this.goal.radius + 0.5;
               if (groundedR && yOkR) {
                  double dx = f.x - (this.goal.x + 0.5);
                  double dz = f.z - (this.goal.z + 0.5);
                  return dx * dx + dz * dz <= rad * rad ? true : this.sweptDist(f, from, this.goal.x + 0.5, this.goal.z + 0.5) < rad;
               } else {
                  return false;
               }
         }
      } else {
         return false;
      }
   }

   private boolean checkPhysicsDrift() {
      PlayerPhysics ph = this.model.phys;
      boolean degraded = this.prevCanSprint && !ph.canSprint
         || this.prevCanDoubleJump && !ph.canDoubleJump
         || this.prevWaterWalk && !ph.sbWaterWalk
         || ph.effSpeed < this.prevEffSpeed * 0.7;
      this.snapshotPhys();
      if (degraded && !this.physDegraded && this.executor != null) {
         boolean depends = false;
         int lo = Math.max(0, this.executor.idx - 1);
         int hi = Math.min(this.path.moves.size(), this.executor.idx + 40);

         for (int i = lo; i < hi; i++) {
            PathEngine.Move mv = this.path.moves.get(i);
            if (mv.type == 3 && mv.gap >= 3 || mv.doubleJump || mv.waterRun) {
               depends = true;
               break;
            }
         }

         if (!depends) {
            return false;
         } else {
            this.physDegraded = true;
            this.replans++;
            this.msg("Effet perdu (vitesse/saut) — §erecalcul du trajet restant§r…");
            this.engine = null;
            this.queuedPath = null;
            this.executor.release();
            this.executor = null;
            this.beginSearch();
            this.status = Navigator.Status.COMPUTING;
            return true;
         }
      } else {
         return false;
      }
   }

   public void tickStart() {
      this.cfg.enforceNavigationPolicy();
      long now = System.currentTimeMillis();
      this.tempBlocked.entrySet().removeIf(e -> e.getValue() < now);
      if (this.cfg.skyblockAuto && ++this.sbTimer >= 20) {
         this.sbTimer = 0;
         SkyblockHelper.refresh(this.mc, this.cfg);
      }

      if (this.status != Navigator.Status.IDLE && this.status != Navigator.Status.ARRIVED && this.status != Navigator.Status.FAILED) {
         LocalPlayer p = this.mc.player;
         if (p == null || this.mc.level == null) {
            this.stop();
         } else if (p.isDeadOrDying()) {
            this.stop();
            this.msg("§cJoueur mort — navigation arrêtée.§r");
         } else {
            Vec3 nowPos = p.position();
            Vec3 sweepFrom = this.prevTickPos != null ? this.prevTickPos : nowPos;
            this.prevTickPos = nowPos;
            if (this.screenGrace > 0) {
               this.screenGrace--;
            }

            if (this.mc.gui.screen() != null && this.screenGrace == 0) {
               this.requestStop("interface ouverte");
            } else if (this.executor != null ? !this.executor.manualInput() : !this.manualKeys()) {
               if (this.manualLook()) {
                  this.requestStop("caméra déplacée à la main");
               }
            } else {
               this.requestStop("déplacement manuel");
            }

            if (this.pendingStop != null) {
               if (this.executor != null && !p.onGround() && !p.isInWater() && --this.stopGrace > 0) {
                  this.executor.tick();
               } else {
                  String reason = this.pendingStop;
                  this.stop();
                  this.msg("Navigation §carrêtée§r — " + reason + ".");
               }
            } else {
               boolean vehiclePause = p.getVehicle() != null;
               boolean levPause = p.hasEffect(MobEffects.LEVITATION);
               boolean elytraPause = p.isFallFlying();
               boolean sleepPause = p.isSleeping();
               if (this.cfg.pauseOnDamage) {
                  if (p.getHealth() < this.lastHealth - 0.5F) {
                     this.damageWindow = 20;
                  }

                  this.lastHealth = p.getHealth();
                  if (this.damageWindow > 0) {
                     this.damageWindow--;
                  }
               }

               boolean damagePause = this.cfg.pauseOnDamage && this.damageWindow > 0 && p.getHealth() < p.getMaxHealth() - 1.0F;
               if (!vehiclePause && !levPause && !elytraPause && !sleepPause && !damagePause) {
                  if (this.status == Navigator.Status.PAUSED) {
                     this.status = this.executor != null ? Navigator.Status.RUNNING : Navigator.Status.COMPUTING;
                  }

                  if (this.goalReached(nowPos, sweepFrom)) {
                     this.arrive();
                  } else if (this.status != Navigator.Status.COMPUTING) {
                     if (this.status == Navigator.Status.RUNNING && this.executor != null) {
                        double dGoal = Math.hypot(p.getX() - (this.goal.x + 0.5), p.getZ() - (this.goal.z + 0.5));
                        if (dGoal < this.bestDistToGoal - 0.01) {
                           this.bestDistToGoal = dGoal;
                           this.bestProgressAt = now;
                        }

                        double rem = this.executor.remainingTicks();
                        if (rem < this.bestRemaining - 0.5) {
                           this.bestRemaining = rem;
                           this.bestProgressAt = now;
                        }

                        if (now - this.bestProgressAt > 45000L) {
                           this.executor.release();
                           this.executor = null;
                           this.status = Navigator.Status.FAILED;
                           this.failedAt = now;
                           this.retryAt = 0L;
                           this.msg("§cAucun progrès vers l'objectif depuis 45 s§r — navigation arrêtée. §7/go§r pour réessayer.");
                           p.playSound(SoundEvents.VILLAGER_NO, 0.6F, 1.0F);
                           return;
                        }

                        if (this.engine != null && this.queuedPath == null) {
                           this.engine.tick();
                           if (this.engine.state != PathEngine.State.SEARCHING) {
                              this.lastExpansions = this.engine.expansions;
                              this.lastComputeMs = this.engine.result() != null ? this.engine.result().timeMs : 0L;
                              this.queuedPath = this.engine.result();
                              this.engine = null;
                           }
                        }

                        boolean replanned = false;
                        if (++this.refreshTimer >= 40) {
                           this.refreshTimer = 0;
                           this.model.refresh(p);
                           replanned = this.checkPhysicsDrift();
                        }

                        if (!replanned && ++this.deepValidateTimer >= 20) {
                           this.deepValidateTimer = 0;
                           int hi = Math.min(this.path.nodes.size(), this.executor.idx + 50);

                           for (int j = this.executor.idx; j < hi; j++) {
                              BlockPos n = this.path.nodes.get(j);
                              if (this.model.loaded(n.getX(), n.getY(), n.getZ()) && this.model.kind(n.getX(), n.getY(), n.getZ()) == 0) {
                                 this.replans++;
                                 this.msg("Obstacle détecté plus loin sur le trajet — §erecalcul préventif§r…");
                                 this.engine = null;
                                 this.queuedPath = null;
                                 this.executor.release();
                                 this.executor = null;
                                 this.beginSearch();
                                 this.status = Navigator.Status.COMPUTING;
                                 replanned = true;
                                 break;
                              }
                           }
                        }

                        if (replanned) {
                           return;
                        }

                        PathExecutor.Signal sig = this.executor.tick();
                        switch (sig) {
                           case PATH_END:
                              if (this.goalReached(nowPos, sweepFrom)) {
                                 this.arrive();
                              } else if (this.queuedPath != null && this.usable(this.queuedPath)) {
                                 PathEngine.PathResult next = this.queuedPath;
                                 this.queuedPath = null;
                                 if (!this.activatePath(next)) {
                                    this.executor = null;
                                    this.status = Navigator.Status.COMPUTING;
                                 }
                              } else if (this.engine != null) {
                                 this.executor = null;
                                 this.status = Navigator.Status.COMPUTING;
                              } else if (this.cfg.autoReplan && this.replans < 256) {
                                 this.replans++;
                                 this.executor = null;
                                 this.queuedPath = null;
                                 this.status = Navigator.Status.COMPUTING;
                              } else {
                                 this.status = Navigator.Status.FAILED;
                                 this.failedAt = now;
                                 this.msg("§cImpossible d'atteindre l'objectif.§r");
                              }
                              break;
                           case FALL_UNSAFE:
                              if (!this.cfg.autoReplan || this.replans >= 256) {
                                 this.status = Navigator.Status.FAILED;
                                 this.failedAt = now;
                                 this.msg("§cChute trop dangereuse — arrêt.§r");
                                 this.executor.release();
                                 return;
                              }

                              this.replans++;
                              BlockPos t = this.executor.target();
                              if (t != null) {
                                 this.tempBlocked.put(t.asLong(), now + 12000L);
                                 if (this.model != null) {
                                    this.model.invalidate();
                                 }
                              }

                              this.msg("Chute évitée (santé insuffisante) — §erecalcul§r…");
                              this.engine = null;
                              this.queuedPath = null;
                              this.executor = null;
                              this.beginSearch();
                              this.status = Navigator.Status.COMPUTING;
                              break;
                           case JUMP_FAILED:
                              if (!this.cfg.autoReplan || this.replans >= 256) {
                                 this.status = Navigator.Status.FAILED;
                                 this.failedAt = now;
                                 this.msg("§cSaut impossible — navigation arrêtée.§r");
                                 this.executor.release();
                                 return;
                              }

                              this.replans++;
                              BlockPos tjf = this.executor.target();
                              if (tjf != null) {
                                 this.tempBlocked.put(tjf.asLong(), now + 20000L);
                                 if (this.model != null) {
                                    this.model.invalidate();
                                 }
                              }

                              this.msg("Saut manqué — §erecalcul immédiat§r (cible écartée).");
                              this.engine = null;
                              this.queuedPath = null;
                              this.executor = null;
                              this.beginSearch();
                              this.status = Navigator.Status.COMPUTING;
                              break;
                           case DEVIATED:
                              if (!this.cfg.autoReplan || this.replans >= 256) {
                                 this.status = Navigator.Status.FAILED;
                                 this.failedAt = now;
                                 this.msg("§cHors trajectoire — navigation arrêtée.§r");
                                 this.executor.release();
                                 return;
                              }

                              this.replans++;
                              this.msg("Hors trajectoire — §erecalcul depuis la position actuelle§r…");
                              this.engine = null;
                              this.queuedPath = null;
                              this.executor = null;
                              this.beginSearch();
                              this.status = Navigator.Status.COMPUTING;
                              break;
                           case STUCK:
                           case BLOCKED:
                              if (!this.cfg.autoReplan || this.replans >= 256) {
                                 this.status = Navigator.Status.FAILED;
                                 this.failedAt = now;
                                 this.msg("§cBlocage — navigation arrêtée.§r");
                                 this.executor.release();
                                 return;
                              }

                              this.replans++;
                              BlockPos tbl = this.executor.target();
                              if (tbl != null && (sig == PathExecutor.Signal.BLOCKED || this.executor.stuckByCollision)) {
                                 this.tempBlocked.put(tbl.asLong(), now + 15000L);
                                 if (this.model != null) {
                                    this.model.invalidate();
                                 }
                              }

                              this.engine = null;
                              this.queuedPath = null;
                              this.executor = null;
                              this.beginSearch();
                              this.status = Navigator.Status.COMPUTING;
                        }
                     }

                     if (this.status == Navigator.Status.COMPUTING && this.executor == null) {
                        this.snapshotRot();
                     }
                  } else if (this.engine == null && !this.beginSearch()) {
                     this.snapshotRot();
                  } else {
                     this.engine.tick();
                     if (this.engine.state == PathEngine.State.SEARCHING) {
                        this.snapshotRot();
                     } else {
                        this.lastExpansions = this.engine.expansions;
                        this.lastComputeMs = this.engine.result() != null ? this.engine.result().timeMs : 0L;
                        PathEngine.PathResult computed = this.engine.result();
                        this.engine = null;
                        if (this.activatePath(computed)) {
                           if (this.path.partial && this.replans == 0) {
                              this.msg("Navigation lancée — calcul du prochain segment en arrière-plan…");
                           }
                        } else if (this.usable(computed) && this.activationFails < 3) {
                           this.activationFails++;
                           this.queuedPath = null;
                           this.snapshotRot();
                        } else {
                           this.activationFails = 0;
                           boolean goalLoaded = this.model.loaded(this.goal.x, this.goal.y == null ? Mth_floor(p.getY()) : this.goal.y, this.goal.z);
                           if (!goalLoaded && this.cfg.autoReplan) {
                              this.status = Navigator.Status.FAILED;
                              this.failedAt = now;
                              this.retryAt = now + 1500L;
                              this.msg("Objectif hors chunks chargés — §enouvelle tentative automatique§r…");
                           } else {
                              this.status = Navigator.Status.FAILED;
                              this.failedAt = now;
                              this.msg("§cAucun chemin trouvé§r (objectif inaccessible ou hors chunks chargés). §7/go <x> [y] <z>§r pour réessayer.");
                              p.playSound(SoundEvents.VILLAGER_NO, 0.6F, 1.0F);
                           }
                        }
                     }
                  }
               } else {
                  if (this.status != Navigator.Status.PAUSED) {
                     this.status = Navigator.Status.PAUSED;
                     this.pauseReason = vehiclePause
                        ? "véhicule"
                        : (levPause ? "lévitation" : (elytraPause ? "élytre" : (sleepPause ? "sommeil" : "dégâts récents")));
                     if (this.executor != null) {
                        this.executor.release();
                     }
                  }
               }
            }
         }
      } else {
         if (this.status == Navigator.Status.FAILED && this.retryAt > 0L && now >= this.retryAt && this.mc.player != null && this.mc.level != null) {
            this.retryAt = 0L;
            this.engine = null;
            this.status = Navigator.Status.COMPUTING;
         }
      }
   }

   private void arrive() {
      if (this.executor != null) {
         this.executor.release();
      }

      this.status = Navigator.Status.ARRIVED;
      this.arrivedAt = System.currentTimeMillis();
      this.executor = null;
      this.path = null;
      this.engine = null;
      this.queuedPath = null;
      LocalPlayer p = this.mc.player;
      if (p != null) {
         p.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.9F, 1.0F);
         p.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.2F);
      }

      this.msg(
         "§aArrivé à destination ✓§r  (§e"
            + this.goal.x
            + ", "
            + (this.goal.y == null ? "auto" : this.goal.y)
            + ", "
            + this.goal.z
            + "§r) — navigation terminée."
      );
   }

   public void tickEnd() {
      if (this.mc.level != null && this.mc.player != null) {
         if (this.cfg.showPath && this.path != null && this.status == Navigator.Status.RUNNING && this.executor != null) {
            try {
               TemporaryCollection c = this.mc.collectPerTickGizmos();

               try {
                  Vec3 prev = this.mc.player.position().add(0.0, 0.1, 0.0);

                  for (int i = this.executor.idx; i < this.path.nodes.size(); i++) {
                     BlockPos n = this.path.nodes.get(i);
                     Vec3 cur = Vec3.atCenterOf(n).add(0.0, -0.4, 0.0);
                     boolean water = i - 1 >= 0 && i - 1 < this.path.moves.size() && this.path.moves.get(i - 1).waterRun;
                     Gizmos.line(prev, cur, water ? -864037656 : -857167780, 2.0F).persistForMillis(120);
                     prev = cur;
                  }

                  if (this.goal != null) {
                     int gy = this.goal.y == null ? this.findColumnTop(this.goal.x, this.goal.z) : this.goal.y;
                     Gizmos.cuboid(new BlockPos(this.goal.x, gy, this.goal.z), GizmoStyle.stroke(-1529764, 2.0F)).persistForMillis(120).setAlwaysOnTop();
                     Gizmos.point(Vec3.atCenterOf(new BlockPos(this.goal.x, gy + 1, this.goal.z)), -1529764, 6.0F).persistForMillis(120).setAlwaysOnTop();
                  }
               } catch (Throwable var8) {
                  if (c != null) {
                     try {
                        c.close();
                     } catch (Throwable var7) {
                        var8.addSuppressed(var7);
                     }
                  }

                  throw var8;
               }

               if (c != null) {
                  c.close();
               }
            } catch (Throwable var9) {
            }
         }
      }
   }

   private int findColumnTop(int x, int z) {
      int y = Mth_floor(this.mc.player.getY());

      for (int dy = 20; dy >= -20; dy--) {
         int yy = y + dy;
         if (this.model != null && this.model.kind(x, yy, z) == 1) {
            return yy - 1;
         }
      }

      return y;
   }

   public double remainingDistance() {
      return this.executor != null ? this.executor.remainingDistance() : 0.0;
   }

   public double remainingTicks() {
      return this.executor != null ? this.executor.remainingTicks() : 0.0;
   }

   public int pathNodes() {
      return this.path != null ? this.path.nodes.size() : 0;
   }

   public int progressIdx() {
      return this.executor != null ? this.executor.idx : 0;
   }

   public static enum Status {
      IDLE,
      COMPUTING,
      RUNNING,
      PAUSED,
      ARRIVED,
      FAILED;
   }
}
