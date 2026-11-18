package dev.amberpath.path;

import java.util.Random;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

public final class AimController {
   private float tYaw;
   private float tPitch;
   private float sYaw;
   private float sPitch;
   private boolean has;
   private boolean smooth = true;
   private boolean precise;
   private double rate = 9.5;
   private double maxRate = 480.0;
   private long lastNs = 0L;
   private long lastFrameMs = 0L;
   private final Random rnd = new Random();
   private final double ph1 = this.rnd.nextDouble() * 6.283;
   private final double ph2 = this.rnd.nextDouble() * 6.283;
   private final double ph3 = this.rnd.nextDouble() * 6.283;
   private long nextGlanceAt = System.currentTimeMillis() + 2500L + this.rnd.nextInt(3000);
   private long glanceStart = -1L;
   private double glanceDur = 1.0;
   private double glanceYaw = 0.0;
   private double glancePitch = 0.0;
   private double walkPhase = this.rnd.nextDouble() * 6.283;
   private float lastAppliedYaw;
   private float lastAppliedPitch;
   private boolean applied;

   public void setTarget(float yaw, float pitch, boolean smooth, double v, double turnSpeed) {
      if (!this.has) {
         this.sYaw = yaw;
         this.sPitch = pitch;
      }

      this.tYaw = yaw;
      this.tPitch = Mth.clamp(pitch, -89.0F, 89.0F);
      this.smooth = smooth;
      this.has = true;
      double ts = Mth.clamp((float)turnSpeed, 0.85F, 1.15F);
      this.rate = Mth.clamp((9.0 + Math.min(v, 1.2) * 2.2) * ts, 8.0, 12.0);
      this.maxRate = Mth.clamp((420.0 + Math.min(v, 1.2) * 120.0) * ts, 380.0, 570.0);
   }

   public void setPrecise(boolean precise) {
      this.precise = precise;
   }

   public void clear() {
      this.has = false;
      this.lastNs = 0L;
      this.applied = false;
      this.glanceStart = -1L;
   }

   public boolean externalLook(LocalPlayer p) {
      return this.applied && p != null
         ? Math.abs(Mth.wrapDegrees(p.getYRot() - this.lastAppliedYaw)) > 1.2F || Math.abs(p.getXRot() - this.lastAppliedPitch) > 1.2F
         : false;
   }

   public void frame(LocalPlayer p) {
      if (this.has && p != null && this.smooth) {
         long now = System.nanoTime();
         double dt = this.lastNs == 0L ? 0.016 : Math.min(0.1, (now - this.lastNs) / 1.0E9);
         this.lastNs = now;
         this.lastFrameMs = System.currentTimeMillis();
         this.apply(p, dt, false);
      }
   }

   public void tickFallback(LocalPlayer p) {
      if (this.has && p != null) {
         if (!this.smooth) {
            this.sYaw = this.tYaw;
            this.sPitch = this.tPitch;
            this.applyDirect(p, this.tYaw, this.tPitch);
         } else {
            if (System.currentTimeMillis() - this.lastFrameMs > 150L) {
               this.apply(p, 0.05, false);
            }
         }
      }
   }

   private void apply(LocalPlayer p, double dt, boolean snap) {
      long nowMs = System.currentTimeMillis();
      double t = nowMs / 1000.0;
      double v = p.getDeltaMovement().horizontalDistance();
      float k = (float)(1.0 - Math.exp(-11.0 * dt));
      this.sYaw = this.sYaw + Mth.wrapDegrees(this.tYaw - this.sYaw) * k;
      this.sPitch = this.sPitch + (this.tPitch - this.sPitch) * k;
      double amp = this.precise ? 0.35 : 1.0;
      double yawNoise = amp * 0.7 * (0.55 * Math.sin(0.6 * t + this.ph1) + 0.3 * Math.sin(1.7 * t + this.ph2) + 0.15 * Math.sin(3.9 * t + this.ph3));
      double pitchNoise = amp * 0.45 * (0.6 * Math.sin(0.5 * t + this.ph2) + 0.4 * Math.sin(2.3 * t + this.ph3));
      double gYaw = 0.0;
      double gPitch = 0.0;
      if (!this.precise) {
         if (this.glanceStart < 0L && nowMs >= this.nextGlanceAt) {
            this.glanceStart = nowMs;
            this.glanceDur = 0.55 + this.rnd.nextDouble() * 0.75;
            this.glanceYaw = (this.rnd.nextBoolean() ? -1 : 1) * (2.0 + this.rnd.nextDouble() * 3.0);
            this.glancePitch = (this.rnd.nextDouble() - 0.5) * 2.0;
            this.nextGlanceAt = nowMs + (long)(3800.0 + this.rnd.nextDouble() * 5200.0);
         }

         if (this.glanceStart >= 0L) {
            double u = (nowMs - this.glanceStart) / 1000.0 / this.glanceDur;
            if (u >= 1.0) {
               this.glanceStart = -1L;
            } else {
               double env = Math.sin(Math.PI * u);
               gYaw = this.glanceYaw * env;
               gPitch = this.glancePitch * env;
            }
         }
      } else {
         this.glanceStart = -1L;
      }

      double bias = 0.0;
      double bob = 0.0;
      if (Math.abs(this.sPitch) < 25.0F) {
         boolean moving = v > 0.08;
         bias = (moving ? 3.2 : 1.2) * (this.precise ? 0.5 : 1.0);
         if (moving) {
            this.walkPhase += v * 20.0 * dt * 2.1;
            bob = Math.sin(this.walkPhase) * 0.4 * (this.precise ? 0.4 : 1.0);
         }
      }

      float desiredYaw = this.sYaw + (float)(yawNoise + gYaw);
      float desiredPitch = Mth.clamp(this.sPitch + (float)(pitchNoise + bias + bob + gPitch), -89.0F, 89.0F);
      float cy = p.getYRot();
      float cp = p.getXRot();
      float dy = Mth.wrapDegrees(desiredYaw - cy);
      float dp = desiredPitch - cp;
      double kk = 1.0 - Math.exp(-this.rate * dt);
      float maxStep = (float)(this.maxRate * dt);
      float sy = Math.abs(dy) < 0.25F ? dy : Mth.clamp((float)(dy * kk), -maxStep, maxStep);
      float sp = Math.abs(dp) < 0.25F ? dp : Mth.clamp((float)(dp * kk), -maxStep, maxStep);
      this.applyDirect(p, cy + sy, cp + sp);
   }

   private void applyDirect(LocalPlayer p, float ny, float np) {
      np = Mth.clamp(np, -89.0F, 89.0F);
      p.setYRot(ny);
      p.setXRot(np);
      p.yRotO = ny;
      p.xRotO = np;
      p.setYHeadRot(ny);
      p.yBodyRot = ny;
      this.lastAppliedYaw = ny;
      this.lastAppliedPitch = np;
      this.applied = true;
   }
}
