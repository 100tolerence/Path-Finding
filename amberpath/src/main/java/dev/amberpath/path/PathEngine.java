package dev.amberpath.path;

import dev.amberpath.config.AmberConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;

public class PathEngine {
   public static final int WALK = 0;
   public static final int ASCEND = 1;
   public static final int DESCEND = 2;
   public static final int PARKOUR = 3;
   public static final int SWIM = 4;
   public static final int CLIMB_UP = 5;
   public static final int CLIMB_DOWN = 6;
   public static final int SCAFF_DOWN = 7;
   public static final int FLY = 8;
   private final WorldModel m;
   private final AmberConfig cfg;
   private final PlayerPhysics ph;
   private final PathEngine.Goal goal;
   private final PathEngine.NodeMap all = new PathEngine.NodeMap();
   private final double w;
   private final PriorityQueue<PathEngine.GNode> open;
   private final PathEngine.GNode start;
   private PathEngine.GNode best;
   public PathEngine.State state = PathEngine.State.SEARCHING;
   public int expansions = 0;
   private final long startMs = System.currentTimeMillis();
   private PathEngine.PathResult result;
   private final double stepTol;
   private static final int[][] DIRS = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

   public PathEngine(WorldModel m, AmberConfig cfg, BlockPos from, PathEngine.Goal goal) {
      this.m = m;
      this.cfg = cfg;
      this.ph = m.phys;
      this.goal = goal;
      this.w = 1.0;
      this.stepTol = Math.max(0.6, this.ph.effectiveStep());
      this.open = new PriorityQueue<>(1024, (a, b) -> {
         int c = Double.compare(a.f, b.f);
         return c != 0 ? c : Double.compare(a.h, b.h);
      });
      int k = m.kind(from.getX(), from.getY(), from.getZ());
      if (k == 0) {
         k = 1;
      }

      this.start = new PathEngine.GNode();
      this.start.x = from.getX();
      this.start.y = from.getY();
      this.start.z = from.getZ();
      this.start.kind = k;
      this.start.floor = m.floorOf(k, this.start.x, this.start.y, this.start.z);
      if (Double.isNaN(this.start.floor)) {
         this.start.floor = this.start.y;
      }

      this.start.g = 0.0;
      this.start.h = this.heuristic(this.start);
      this.start.f = this.w * this.start.h;
      this.all.put(this.start.key(), this.start);
      this.open.add(this.start);
      this.best = this.start;
   }

   private double heuristic(PathEngine.GNode n) {
      double dx = Math.abs((double)n.x - this.goal.x);
      double dz = Math.abs((double)n.z - this.goal.z);
      double horizontal = Math.hypot(dx, dz);
      double vertical = this.goal.y == null ? 0.0 : Math.abs((double)n.y - this.goal.y.intValue());
      if (this.goal.mode == 2) {
         horizontal = Math.max(0.0, horizontal - this.goal.radius);
         vertical = Math.max(0.0, vertical - this.goal.radius);
         vertical = Math.max(0.0, vertical);
      }

      double distance = Math.hypot(horizontal, vertical);
      double minCostPerBlock = Math.min(this.ph.sprintJumpCost, Math.min(this.ph.swimCost, this.ph.flyCost));
      minCostPerBlock = Math.max(0.001, minCostPerBlock);
      return distance * minCostPerBlock;
   }

   private boolean isGoal(PathEngine.GNode n) {
      switch (this.goal.mode) {
         case 0:
            if (n.x == this.goal.x && n.z == this.goal.z && n.kind == 1) {
               return this.goal.y == null || Math.abs(n.floor - (this.goal.y + 1)) < 0.75;
            }

            return false;
         case 1:
            return n.x == this.goal.x && n.z == this.goal.z && (this.goal.y == null || n.y == this.goal.y);
         default:
            double dx = n.x - this.goal.x;
            double dz = n.z - this.goal.z;
            return dx * dx + dz * dz > (double)this.goal.radius * this.goal.radius
               ? false
               : this.goal.y == null || Math.abs(n.y - this.goal.y) <= this.goal.radius;
      }
   }

   public void tick() {
      if (this.state == PathEngine.State.SEARCHING) {
         long deadline = System.nanoTime() + this.cfg.budgetMs * 1000000L;
         if (this.cfg.partialAfterMs > 0
            && this.best != this.start
            && this.best.h < this.start.h - 3.0
            && System.currentTimeMillis() - this.startMs > this.cfg.partialAfterMs) {
            this.finish(this.best, true);
         } else {
            int checks = 0;

            while (!this.open.isEmpty()) {
               if ((++checks & 31) == 0 && System.nanoTime() > deadline) {
                  return;
               }

               PathEngine.GNode n = this.open.poll();
               if (!n.closed && this.all.get(n.key()) == n) {
                  n.closed = true;
                  this.expansions++;
                  if (this.isGoal(n)) {
                     this.finish(n, false);
                     return;
                  }

                  if (n.h < this.best.h) {
                     this.best = n;
                  }

                  if (this.expansions >= this.cfg.maxNodes) {
                     this.finish(this.best, this.best != this.start);
                     return;
                  }

                  this.expand(n);
               }
            }

            this.finish(this.best, this.best != this.start);
         }
      }
   }

   private void offer(PathEngine.GNode from, PathEngine.Move mv, int x, int y, int z, int kind, double floor) {
      long key = BlockPos.asLong(x, y, z);
      double ng = from.g + mv.cost;
      PathEngine.GNode ex = this.all.get(key);
      if (ex == null || !(ex.g <= ng + 1.0E-6)) {
         PathEngine.GNode n = new PathEngine.GNode();
         n.x = x;
         n.y = y;
         n.z = z;
         n.kind = kind;
         n.floor = floor;
         n.g = ng;
         n.parent = from;
         n.move = mv;
         n.run = mv.type == 0 && mv.sprint ? Math.min(6, from.run + 1) : 0;
         n.h = this.heuristic(n);
         n.f = n.g + this.w * n.h;
         this.all.put(key, n);
         this.open.add(n);
      }
   }

   private int gapFlat() {
      return this.cfg.adaptiveParkour ? Math.max(this.cfg.maxGap, this.ph.maxGapFlat) : this.cfg.maxGap;
   }

   private int gapDown() {
      return this.cfg.adaptiveParkour ? Math.max(this.cfg.maxGap + 1, this.ph.maxGapDown) : this.cfg.maxGap + 1;
   }

   private double takeoffVel(PathEngine.GNode n, double iceF) {
      double mom = n.run >= 3 ? 1.0 : 0.62 + 0.13 * n.run;
      return this.ph.sprintVel * mom * iceF;
   }

   private int exitCount(int x, int y, int z) {
      int c = 0;
      if (this.m.kind(x + 1, y, z) != 0) {
         c++;
      }

      if (this.m.kind(x - 1, y, z) != 0) {
         c++;
      }

      if (this.m.kind(x, y, z + 1) != 0) {
         c++;
      }

      if (this.m.kind(x, y, z - 1) != 0) {
         c++;
      }

      return c;
   }

   private boolean goalCell(int x, int y, int z, double floor) {
      switch (this.goal.mode) {
         case 0:
            return x == this.goal.x && z == this.goal.z && (this.goal.y == null || Math.abs(floor - (this.goal.y + 1)) < 0.75);
         case 1:
            return x == this.goal.x && z == this.goal.z && (this.goal.y == null || y == this.goal.y);
         default:
            double dx = x - this.goal.x;
            double dz = z - this.goal.z;
            return dx * dx + dz * dz <= (double)this.goal.radius * this.goal.radius && (this.goal.y == null || Math.abs(y - this.goal.y) <= this.goal.radius);
      }
   }

   private void expand(PathEngine.GNode n) {
      if (n.kind == 5) {
         this.expandFly(n);
      } else if (n.kind == 6) {
         this.expandSurface(n);
      } else {
         boolean sprintOk = this.cfg.sprint && this.ph.canSprint;
         boolean honeyBelow = this.m.honey(n.x, n.y - 1, n.z);
         boolean iceBelow = this.m.ice(n.x, n.y, n.z);
         double f0 = n.floor;
         double walkBase = sprintOk ? this.ph.sprintCost : this.ph.walkCost;
         double budget = this.ph.health + this.ph.absorption - 2.0 * this.cfg.keepHearts;
         if (n.kind == 4) {
            int ku = this.m.kind(n.x, n.y + 1, n.z);
            if (ku == 4) {
               this.offer(n, new PathEngine.Move(5, 8.0), n.x, n.y + 1, n.z, ku, n.y + 1);
            } else if (ku == 1) {
               this.offer(n, new PathEngine.Move(5, 11.0), n.x, n.y + 1, n.z, ku, this.m.floorOf(ku, n.x, n.y + 1, n.z));
            }

            int kd = this.m.kind(n.x, n.y - 1, n.z);
            if (kd == 4) {
               boolean sc = this.m.scaff(n.x, n.y, n.z);
               this.offer(n, new PathEngine.Move(sc ? 7 : 6, sc ? 3.5 : 6.7), n.x, n.y - 1, n.z, kd, n.y - 1);
            }
         }

         if (n.kind == 2 || n.kind == 3) {
            double base = n.kind == 3 ? this.ph.lavaCost : (this.m.water(n.x, n.y + 1, n.z) ? this.ph.swimCost : this.ph.swimCost * 0.8);
            int kux = this.m.kind(n.x, n.y + 1, n.z);
            if (kux == 2 || kux == 3) {
               this.offer(n, new PathEngine.Move(4, base + 1.0), n.x, n.y + 1, n.z, kux, n.y + 1);
            } else if (kux == 6) {
               this.offer(n, new PathEngine.Move(4, base * 0.8), n.x, n.y + 1, n.z, kux, n.y + 1 + 0.875);
            } else if (kux == 1 && this.m.floorOf(kux, n.x, n.y + 1, n.z) <= n.y + 1.2) {
               this.offer(n, new PathEngine.Move(1, 9.0), n.x, n.y + 1, n.z, kux, this.m.floorOf(kux, n.x, n.y + 1, n.z));
            }

            int kd = this.m.kind(n.x, n.y - 1, n.z);
            if (kd == 2 || kd == 3) {
               this.offer(n, new PathEngine.Move(4, base), n.x, n.y - 1, n.z, kd, n.y - 1);
            }
         }

         int maxDrop = (int)Math.min((double)this.cfg.maxFallScan, this.m.maxDrop());
         int clearanceTop = Math.min(8, (int)Math.ceil(this.ph.jumpPeak) + 1);

         for (int[] d : DIRS) {
            int dx = d[0];
            int dz = d[1];
            boolean diag = dx != 0 && dz != 0;
            if (!diag || this.cfg.diagonals) {
               int nx = n.x + dx;
               int nz = n.z + dz;
               if (!diag
                  || this.m.passable(nx, n.y, n.z) && this.m.passable(nx, n.y + 1, n.z) && this.m.passable(n.x, n.y, nz) && this.m.passable(n.x, n.y + 1, nz)) {
                  double dirF = diag ? 1.4142 : 1.0;
                  if (n.kind != 2 && n.kind != 3) {
                     boolean nextWalk = false;

                     for (int ty = n.y + 1; ty >= n.y - maxDrop && (ty >= n.y || this.m.passable(nx, ty + 1, nz)); ty--) {
                        int k = this.m.kind(nx, ty, nz);
                        if (k != 0) {
                           double f1 = this.m.floorOf(k, nx, ty, nz);
                           if (k == 6) {
                              if (ty == n.y) {
                                 double df = f1 - f0;
                                 if (df <= this.stepTol && df >= -0.6) {
                                    PathEngine.Move mv = new PathEngine.Move(0, this.ph.waterRunCost * dirF);
                                    mv.sprint = true;
                                    mv.waterRun = true;
                                    this.offer(n, mv, nx, ty, nz, k, f1);
                                    nextWalk = true;
                                 }
                              } else if (ty == n.y - 1 && this.cfg.descend) {
                                 double drop = f0 - f1;
                                 if (drop <= 2.6) {
                                    PathEngine.Move mv = new PathEngine.Move(2, this.m.fallTime(drop) + 1.0);
                                    mv.drop = 0.0;
                                    mv.sprint = true;
                                    mv.waterRun = true;
                                    this.offer(n, mv, nx, ty, nz, k, f1);
                                 }
                              }
                           } else if (k != 2 && k != 3) {
                              if (k == 4) {
                                 if (ty == n.y) {
                                    this.offer(n, new PathEngine.Move(0, 5.0 * dirF + 2.0), nx, ty, nz, k, ty);
                                 }
                              } else {
                                 double df = f1 - f0;
                                 if (ty == n.y && Math.abs(df) <= this.stepTol) {
                                    int exits = this.exitCount(nx, ty, nz);
                                    if (exits != 0 || this.goalCell(nx, ty, nz, f1)) {
                                       double slow = this.m.surfaceFactor(nx, ty, nz) * this.m.surfaceFactor(n.x, n.y, n.z);
                                       double cost = walkBase * dirF * Math.max(1.0, slow) + this.m.cellExtra(nx, ty, nz);
                                       if (exits == 1 && !this.goalCell(nx, ty, nz, f1)) {
                                          cost += 6.0;
                                       }

                                       PathEngine.Move mv = new PathEngine.Move(0, cost);
                                       mv.sprint = sprintOk;
                                       mv.door = this.m.doorClosed(nx, ty, nz);
                                       this.offer(n, mv, nx, ty, nz, k, f1);
                                       nextWalk = true;
                                    }
                                 } else if (df > this.stepTol
                                    && df <= this.ph.jumpPeak - 0.08
                                    && ty == n.y + 1
                                    && !honeyBelow
                                    && this.m.passable(n.x, n.y + 2, n.z)) {
                                    PathEngine.Move mv = new PathEngine.Move(1, sprintOk ? 10.0 : 11.0);
                                    mv.sprint = sprintOk;
                                    this.offer(n, mv, nx, ty, nz, k, f1);
                                 } else if (df < -this.stepTol && this.cfg.descend) {
                                    double drop = -df;
                                    double mult = this.m.landingMult(nx, ty, nz);
                                    double dmg = this.ph.fallDamage(drop) * mult;
                                    boolean safe = mult == 0.0;
                                    if ((safe || this.cfg.riskyFalls || !(drop > this.cfg.maxFall)) && (!(dmg > 0.0) || !(dmg >= budget))) {
                                       double penalty = dmg > 0.0 ? dmg * 8.0 : (drop > 3.5 ? 6.0 * (drop - 3.0) : 0.0);
                                       PathEngine.Move mv = new PathEngine.Move(2, this.m.fallTime(drop) + 3.0 + penalty);
                                       mv.drop = drop;
                                       this.offer(n, mv, nx, ty, nz, k, f1);
                                       break;
                                    }
                                 }
                              }
                           } else if (ty <= n.y && this.cfg.swim) {
                              double drop = Math.max(0.0, f0 - ty);
                              this.offer(n, new PathEngine.Move(4, this.m.fallTime(drop) + 3.0), nx, ty, nz, k, ty);
                           }
                        }
                     }

                     if (this.cfg.parkour && sprintOk && !honeyBelow && n.kind == 1 && (!iceBelow || this.cfg.iceParkour) && this.m.passable(n.x, n.y + 2, n.z)
                        )
                      {
                        double iceF = iceBelow ? 1.2 : 1.0;
                        double vT = this.takeoffVel(n, iceF);
                        int gFlat = (int)(this.gapFlat() * iceF);
                        int gDown = (int)(this.gapDown() * iceF);
                        int reachFlat = (int)Math.floor(vT * this.ph.airTimeFlat - 0.45);
                        int reachDown = (int)Math.floor(vT * this.ph.airTimeDown1 - 0.45);
                        int maxFlat = Math.min(gFlat + 1, reachFlat);
                        int maxDown = Math.min(gDown + 1, reachDown);
                        if (!nextWalk) {
                           for (int dy = 0; dy >= -1; dy--) {
                              int maxD = dy == 0 ? maxFlat : maxDown;

                              for (int D = 2; D <= maxD; D++) {
                                 int tx = n.x + dx * D;
                                 int tz = n.z + dz * D;
                                 int k = this.m.kind(tx, n.y + dy, tz);
                                 if (k == 1 || k == 6) {
                                    double f1 = this.m.floorOf(k, tx, n.y + dy, tz);
                                    double df = f1 - f0;
                                    if ((dy != 0 || !(Math.abs(df) > 0.6)) && (dy != -1 || !(df > -0.4) && !(df < -(gDown > 4 ? 4.5 : 2.6)))) {
                                       if (dy == -1) {
                                          double dDmg = this.ph.fallDamage(-df) * this.m.landingMult(tx, n.y + dy, tz);
                                          if (dDmg > 0.0 && dDmg >= budget) {
                                             continue;
                                          }
                                       }

                                       if (this.clearPath(n, dx, dz, D, clearanceTop)) {
                                          PathEngine.Move mv = new PathEngine.Move(3, D * this.ph.sprintJumpCost + 5.0 + 2.0);
                                          mv.gap = D;
                                          mv.sprint = true;
                                          mv.waterRun = k == 6;
                                          this.offer(n, mv, tx, n.y + dy, tz, k, f1);
                                       }
                                    }
                                 }
                              }
                           }

                           if (this.ph.canDoubleJump && this.dy0Allowed(n)) {
                              int reachDj = (int)Math.floor(vT * this.ph.airTimeDouble - 0.45);
                              int maxDj = Math.min(reachDj, gFlat + 5);
                              int topDj = Math.min(10, (int)Math.ceil(this.ph.jumpPeakDouble) + 1);

                              for (int Dx = Math.max(2, maxFlat + 1); Dx <= maxDj; Dx++) {
                                 int tx = n.x + dx * Dx;
                                 int tz = n.z + dz * Dx;
                                 int k = this.m.kind(tx, n.y, tz);
                                 if (k == 1) {
                                    double f1 = this.m.floorOf(k, tx, n.y, tz);
                                    if (!(Math.abs(f1 - f0) > 0.6) && this.clearPath(n, dx, dz, Dx, topDj)) {
                                       PathEngine.Move mv = new PathEngine.Move(3, Dx * this.ph.sprintJumpCost + 5.0 + 2.0 + 6.0);
                                       mv.gap = Dx;
                                       mv.sprint = true;
                                       mv.doubleJump = true;
                                       this.offer(n, mv, tx, n.y, tz, k, f1);
                                    }
                                 }
                              }
                           }
                        }

                        int maxUp = this.ph.maxAscend;
                        int maxDUp = Math.min(gFlat + 1, (int)Math.floor(vT * this.ph.airTimeFlat * 0.62 - 0.45));

                        for (int R = 1; R <= maxUp; R++) {
                           if (R < 2 || !(this.ph.jumpPeak < R + 0.3)) {
                              int minD = 2;
                              int maxD = maxDUp;

                              for (int Dxx = minD; Dxx <= maxD; Dxx++) {
                                 int tx = n.x + dx * Dxx;
                                 int tz = n.z + dz * Dxx;
                                 int k = this.m.kind(tx, n.y + R, tz);
                                 if (k == 1) {
                                    double f1 = this.m.floorOf(k, tx, n.y + R, tz);
                                    double df = f1 - f0;
                                    if (!(df < R - 0.6)
                                       && !(df > Math.min(R + 0.35, this.ph.jumpPeak - 0.05))
                                       && this.clearPath(n, dx, dz, Dxx, clearanceTop + R)) {
                                       PathEngine.Move mv = new PathEngine.Move(3, Dxx * this.ph.sprintJumpCost + 8.0 + R * 2);
                                       mv.gap = Dxx;
                                       mv.sprint = true;
                                       this.offer(n, mv, tx, n.y + R, tz, k, f1);
                                    }
                                 }
                              }
                           }
                        }
                     }
                  } else {
                     double basex = n.kind == 3 ? this.ph.lavaCost : (this.m.water(n.x, n.y + 1, n.z) ? this.ph.swimCost : this.ph.swimCost * 0.8);

                     for (int tyx = n.y + 1; tyx >= n.y - 1; tyx--) {
                        int k = this.m.kind(nx, tyx, nz);
                        if (k == 2 || k == 3) {
                           this.offer(n, new PathEngine.Move(4, basex * dirF + this.m.cellExtra(nx, tyx, nz)), nx, tyx, nz, k, tyx);
                        } else if (k == 6 && tyx >= n.y) {
                           this.offer(n, new PathEngine.Move(4, basex * dirF * 0.8), nx, tyx, nz, k, tyx + 0.875);
                        } else if (k == 1 && tyx >= n.y && this.m.floorOf(k, nx, tyx, nz) - n.y <= 1.2) {
                           this.offer(n, new PathEngine.Move(1, 9.0 * dirF), nx, tyx, nz, k, this.m.floorOf(k, nx, tyx, nz));
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private boolean dy0Allowed(PathEngine.GNode n) {
      return n.kind == 1 && !this.m.honey(n.x, n.y - 1, n.z);
   }

   private void expandSurface(PathEngine.GNode n) {
      double f0 = n.floor;
      boolean sprintOk = this.cfg.sprint && this.ph.canSprint;
      double walkBase = sprintOk ? this.ph.sprintCost : this.ph.walkCost;

      for (int[] d : DIRS) {
         int dx = d[0];
         int dz = d[1];
         boolean diag = dx != 0 && dz != 0;
         if (!diag || this.cfg.diagonals) {
            int nx = n.x + dx;
            int nz = n.z + dz;
            if (!diag || this.m.passable(nx, n.y + 1, n.z) && this.m.passable(n.x, n.y + 1, nz)) {
               double dirF = diag ? 1.4142 : 1.0;

               for (int ty = n.y + 1; ty >= n.y - 1; ty--) {
                  int k = this.m.kind(nx, ty, nz);
                  if (k == 6 && ty == n.y) {
                     PathEngine.Move mv = new PathEngine.Move(0, this.ph.waterRunCost * dirF);
                     mv.sprint = true;
                     mv.waterRun = true;
                     this.offer(n, mv, nx, ty, nz, k, ty + 0.875);
                  } else if (k == 1) {
                     double f1 = this.m.floorOf(k, nx, ty, nz);
                     double df = f1 - f0;
                     if (df <= this.stepTol && df >= -0.6) {
                        PathEngine.Move mv = new PathEngine.Move(
                           0, walkBase * dirF * Math.max(1.0, this.m.surfaceFactor(nx, ty, nz)) + this.m.cellExtra(nx, ty, nz)
                        );
                        mv.sprint = sprintOk;
                        mv.door = this.m.doorClosed(nx, ty, nz);
                        this.offer(n, mv, nx, ty, nz, k, f1);
                     } else if (df < -0.6 && df >= -1.4 && this.cfg.descend) {
                        PathEngine.Move mv = new PathEngine.Move(2, this.m.fallTime(-df) + 1.0);
                        mv.drop = -df;
                        this.offer(n, mv, nx, ty, nz, k, f1);
                     }
                  } else if (k == 2 && ty <= n.y) {
                     this.offer(n, new PathEngine.Move(4, this.ph.swimCost * dirF + 2.0), nx, ty, nz, k, ty);
                  } else if (k == 4 && ty == n.y) {
                     this.offer(n, new PathEngine.Move(0, 5.0 * dirF + 2.0), nx, ty, nz, k, ty);
                  }
               }
            }
         }
      }
   }

   private boolean clearPath(PathEngine.GNode n, int dx, int dz, int D, int top) {
      for (int c = 1; c < D; c++) {
         int cx = n.x + dx * c;
         int cz = n.z + dz * c;

         for (int k = 1; k <= top; k++) {
            if (!this.m.passable(cx, n.y + k, cz)) {
               return false;
            }
         }
      }

      return true;
   }

   private void expandFly(PathEngine.GNode n) {
      for (int dx = -1; dx <= 1; dx++) {
         for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
               if (dx != 0 || dy != 0 || dz != 0) {
                  int nx = n.x + dx;
                  int ny = n.y + dy;
                  int nz = n.z + dz;
                  int k = this.m.kind(nx, ny, nz);
                  if (k != 0) {
                     double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                     this.offer(n, new PathEngine.Move(8, this.ph.flyCost * dist), nx, ny, nz, k, ny);
                  }
               }
            }
         }
      }
   }

   private boolean straightOk(PathEngine.GNode a, PathEngine.GNode b, List<PathEngine.GNode> chain, int ia, int ib) {
      if (a.kind != 1 || b.kind != 1 || a.y != b.y) {
         return false;
      } else if (b.move != null && b.move.type == 0 && !b.move.door && !b.move.waterRun) {
         for (int t = ia + 1; t <= ib; t++) {
            PathEngine.GNode c = chain.get(t);
            if (c.kind != 1 || c.y != a.y || c.move == null || c.move.type != 0 || c.move.door || c.move.waterRun) {
               return false;
            }
         }

         double ax = a.x + 0.5;
         double az = a.z + 0.5;
         double bx = b.x + 0.5;
         double bz = b.z + 0.5;
         double dist = Math.hypot(bx - ax, bz - az);
         int steps = (int)Math.ceil(dist / 0.3);

         for (int s = 1; s <= steps; s++) {
            double tx = (double)s / steps;
            int cx = (int)Math.floor(ax + (bx - ax) * tx);
            int cz = (int)Math.floor(az + (bz - az) * tx);
            int k = this.m.kind(cx, a.y, cz);
            if (k != 1) {
               return false;
            }

            double f = this.m.floorOf(k, cx, a.y, cz);
            if (Math.abs(f - a.floor) > this.stepTol + 0.05) {
               return false;
            }

            if (this.m.doorClosed(cx, a.y, cz)) {
               return false;
            }

            if (this.m.cellExtra(cx, a.y, cz) > 12.0) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private void finish(PathEngine.GNode end, boolean partial) {
      this.result = new PathEngine.PathResult();
      this.result.partial = partial;
      this.result.expansions = this.expansions;
      this.result.timeMs = System.currentTimeMillis() - this.startMs;
      List<PathEngine.GNode> chain = new ArrayList<>();

      for (PathEngine.GNode cur = end; cur != null; cur = cur.parent) {
         chain.add(cur);
      }

      Collections.reverse(chain);
      if (this.cfg.smoothPath && chain.size() > 2) {
         boolean sprintOk = this.cfg.sprint && this.ph.canSprint;
         List<PathEngine.GNode> out = new ArrayList<>(chain.size());
         out.add(chain.get(0));
         int i = 0;
         int removed = 0;

         while (i < chain.size() - 1) {
            int bestIdx = i + 1;
            int lim = Math.min(chain.size() - 1, i + 24);

            for (int j = lim; j > i + 1; j--) {
               if (this.straightOk(chain.get(i), chain.get(j), chain, i, j)) {
                  bestIdx = j;
                  break;
               }
            }

            PathEngine.GNode b = chain.get(bestIdx);
            if (bestIdx > i + 1) {
               PathEngine.GNode a = chain.get(i);
               double dist = Math.hypot(b.x - a.x, b.z - a.z);
               PathEngine.Move mv = new PathEngine.Move(0, dist * (sprintOk ? this.ph.sprintCost : this.ph.walkCost));
               mv.sprint = sprintOk;
               b.move = mv;
               removed += bestIdx - i - 1;
            }

            out.add(b);
            i = bestIdx;
         }

         chain = out;
         this.result.smoothed = removed;
      }

      for (int i = 0; i < chain.size(); i++) {
         PathEngine.GNode g = chain.get(i);
         this.result.nodes.add(new BlockPos(g.x, g.y, g.z));
         if (g.move != null) {
            this.result.moves.add(g.move);
            this.result.cost = this.result.cost + g.move.cost;
         }
      }

      this.state = partial ? PathEngine.State.PARTIAL : PathEngine.State.FOUND;
      if (end == this.start && !partial) {
         this.state = PathEngine.State.FAILED;
      }
   }

   public PathEngine.PathResult result() {
      return this.result;
   }

   private static class GNode {
      int x;
      int y;
      int z;
      int kind;
      double floor;
      double g;
      double h;
      double f;
      PathEngine.GNode parent;
      PathEngine.Move move;
      boolean closed;
      int run;

      long key() {
         return BlockPos.asLong(this.x, this.y, this.z);
      }

      double f(double w) {
         return this.g + w * this.h;
      }
   }

   public static class Goal {
      public final int mode;
      public final int x;
      public final int z;
      public final int radius;
      public final Integer y;

      public Goal(int mode, int x, Integer y, int z, int radius) {
         this.mode = mode;
         this.x = x;
         this.y = y;
         this.z = z;
         this.radius = radius;
      }
   }

   public static class Move {
      public int type;
      public double cost;
      public int gap;
      public boolean sprint;
      public boolean door;
      public boolean waterRun;
      public boolean doubleJump;
      public double drop;

      Move(int type, double cost) {
         this.type = type;
         this.cost = cost;
      }
   }

   private static final class NodeMap {
      private long[] keys = new long[16384];
      private PathEngine.GNode[] vals = new PathEngine.GNode[16384];
      private int size;
      private int mask = 16383;

      NodeMap() {
         Arrays.fill(this.keys, Long.MIN_VALUE);
      }

      private static int hash(long k) {
         return (int)(k * -7046029254386353131L >>> 32);
      }

      PathEngine.GNode get(long k) {
         int i = hash(k) & this.mask;

         while (true) {
            long kk = this.keys[i];
            if (kk == k) {
               return this.vals[i];
            }

            if (kk == Long.MIN_VALUE) {
               return null;
            }

            i = i + 1 & this.mask;
         }
      }

      void put(long k, PathEngine.GNode v) {
         if ((this.size + 1) * 2 > this.keys.length) {
            this.grow();
         }

         int i = hash(k) & this.mask;

         while (true) {
            long kk = this.keys[i];
            if (kk == k) {
               this.vals[i] = v;
               return;
            }

            if (kk == Long.MIN_VALUE) {
               this.keys[i] = k;
               this.vals[i] = v;
               this.size++;
               return;
            }

            i = i + 1 & this.mask;
         }
      }

      private void grow() {
         long[] ok = this.keys;
         PathEngine.GNode[] ov = this.vals;
         this.keys = new long[ok.length * 2];
         this.vals = new PathEngine.GNode[ok.length * 2];
         Arrays.fill(this.keys, Long.MIN_VALUE);
         this.mask = this.keys.length - 1;
         this.size = 0;

         for (int j = 0; j < ok.length; j++) {
            if (ok[j] != Long.MIN_VALUE) {
               this.put(ok[j], ov[j]);
            }
         }
      }
   }

   public static class PathResult {
      public List<BlockPos> nodes = new ArrayList<>();
      public List<PathEngine.Move> moves = new ArrayList<>();
      public double cost;
      public boolean partial;
      public int expansions;
      public long timeMs;
      public int smoothed;
   }

   public static enum State {
      SEARCHING,
      FOUND,
      PARTIAL,
      FAILED;
   }
}
