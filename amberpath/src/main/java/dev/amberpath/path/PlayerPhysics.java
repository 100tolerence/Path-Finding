package dev.amberpath.path;

import dev.amberpath.config.AmberConfig;
import dev.amberpath.skyblock.SkyblockHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

public final class PlayerPhysics {
   public final boolean creative;
   public final boolean spectator;
   public final boolean flying;
   public final boolean elytra;
   public final boolean sleeping;
   public final boolean fireResist;
   public final boolean slowFalling;
   public final boolean levitation;
   public final boolean dolphinsGrace;
   public final boolean conduitPower;
   public final boolean waterBreathing;
   public final int speedAmp;
   public final int slownessAmp;
   public final int jumpAmp;
   public final boolean canSprint;
   public final boolean leatherBoots;
   public final int depthStrider;
   public final int soulSpeed;
   public final int featherFalling;
   public final int protection;
   public final int swiftSneak;
   public final int frostWalker;
   public final double moveSpeed;
   public final double flySpeed;
   public final double jumpStrength;
   public final double gravity;
   public final double safeFall;
   public final double stepHeight;
   public final double fallDamageMult;
   public final double waterEff;
   public final double sneakSpeedAttr;
   public final double speedFactor;
   public final boolean sbActive;
   public final int sbSpeedStat;
   public final double effSpeed;
   public final boolean sbAutoStep;
   public final boolean sbWaterWalk;
   public final double waterRunCost;
   public final double health;
   public final double absorption;
   public final double maxHealth;
   public final double jumpPeak;
   public final double airTimeFlat;
   public final double airTimeDown1;
   public final double sprintJumpDist;
   public final double walkJumpDist;
   public final double sprintVel;
   public final double walkVel;
   public final int maxGapFlat;
   public final int maxGapDown;
   public final int maxAscend;
   public final double sneakFactor;
   public final double fallMult;
   public final boolean canDoubleJump;
   public final double airTimeDouble;
   public final double jumpPeakDouble;
   public final double walkCost;
   public final double sprintCost;
   public final double sprintJumpCost;
   public final double swimCost;
   public final double lavaCost;
   public final double flyCost;

   private PlayerPhysics(Player p, AmberConfig cfg) {
      this.creative = p.isCreative();
      this.spectator = p.isSpectator();
      this.flying = p.getAbilities().flying;
      this.elytra = p.isFallFlying();
      this.sleeping = p.isSleeping();
      this.fireResist = p.hasEffect(MobEffects.FIRE_RESISTANCE);
      this.slowFalling = p.hasEffect(MobEffects.SLOW_FALLING);
      this.levitation = p.hasEffect(MobEffects.LEVITATION);
      this.dolphinsGrace = p.hasEffect(MobEffects.DOLPHINS_GRACE);
      this.conduitPower = p.hasEffect(MobEffects.CONDUIT_POWER);
      this.waterBreathing = p.hasEffect(MobEffects.WATER_BREATHING);
      this.speedAmp = amp(p, MobEffects.SPEED);
      this.slownessAmp = amp(p, MobEffects.SLOWNESS);
      this.jumpAmp = amp(p, MobEffects.JUMP_BOOST);
      this.canSprint = p.getFoodData().getFoodLevel() > 6 || this.creative;
      this.leatherBoots = p.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS);
      Registry<Enchantment> reg = p.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
      this.depthStrider = ench(reg, p, Enchantments.DEPTH_STRIDER);
      this.soulSpeed = ench(reg, p, Enchantments.SOUL_SPEED);
      this.featherFalling = ench(reg, p, Enchantments.FEATHER_FALLING);
      this.protection = ench(reg, p, Enchantments.PROTECTION);
      this.swiftSneak = ench(reg, p, Enchantments.SWIFT_SNEAK);
      this.frostWalker = ench(reg, p, Enchantments.FROST_WALKER);
      this.moveSpeed = attr(p, Attributes.MOVEMENT_SPEED, 0.1);
      this.flySpeed = attr(p, Attributes.FLYING_SPEED, 0.05);
      this.jumpStrength = attr(p, Attributes.JUMP_STRENGTH, 0.42);
      this.gravity = attr(p, Attributes.GRAVITY, 0.08);
      this.safeFall = attr(p, Attributes.SAFE_FALL_DISTANCE, 3.0);
      this.stepHeight = attr(p, Attributes.STEP_HEIGHT, 0.6);
      this.fallDamageMult = attrZero(p, Attributes.FALL_DAMAGE_MULTIPLIER, 1.0);
      this.waterEff = attrZero(p, Attributes.WATER_MOVEMENT_EFFICIENCY, 0.0);
      this.sneakSpeedAttr = attr(p, Attributes.SNEAKING_SPEED, 0.3);
      this.speedFactor = Math.max(0.05, this.moveSpeed / 0.1);
      Minecraft mc = Minecraft.getInstance();
      this.sbActive = SkyblockHelper.active(mc, cfg);
      this.sbSpeedStat = this.sbActive && cfg.sbUseTabSpeed ? SkyblockHelper.speedStat() : 0;
      double tabFactor = this.sbSpeedStat > 0 ? this.sbSpeedStat / 100.0 : 0.0;
      this.effSpeed = Math.max(this.speedFactor, tabFactor);
      this.sbAutoStep = this.sbActive && cfg.sbAutoStep && (cfg.sbForceStep || this.effSpeed * 100.0 >= cfg.sbStepSpeed);
      this.sbWaterWalk = this.sbActive && cfg.sbWaterWalk && (cfg.sbForceWater || this.effSpeed * 100.0 >= cfg.sbWaterSpeed);
      this.health = p.getHealth();
      this.absorption = p.getAbsorptionAmount();
      this.maxHealth = p.getMaxHealth();
      double g = this.slowFalling ? 0.01 : this.gravity;
      double[] sim = simulateJump(this.jumpStrength, g);
      this.jumpPeak = sim[0];
      this.airTimeFlat = sim[1];
      this.airTimeDown1 = sim[2];
      double vSprint = 0.36 * this.effSpeed;
      double vWalk = 0.17 * this.effSpeed;
      this.sprintVel = vSprint;
      this.walkVel = vWalk;
      this.sprintJumpDist = vSprint * this.airTimeFlat;
      this.walkJumpDist = vWalk * this.airTimeFlat;
      int cap = 24;
      this.maxGapFlat = clampInt((int)Math.floor(this.sprintJumpDist - 0.25) - 1, 1, cap);
      this.maxGapDown = clampInt((int)Math.floor(vSprint * this.airTimeDown1 - 0.25) - 1, 1, cap + 4);
      this.maxAscend = clampInt((int)Math.floor(this.jumpPeak - 0.05), 1, 8);
      this.canDoubleJump = cfg.doubleJump
         && !this.slowFalling
         && !this.levitation
         && (this.jumpAmp >= 2 || this.jumpAmp >= 1 && this.effSpeed >= 1.5 || this.sbActive && this.sbSpeedStat >= 250);
      double[] dj = simulateDoubleJump(this.jumpStrength, g);
      this.jumpPeakDouble = dj[0];
      this.airTimeDouble = dj[1];
      this.sneakFactor = Math.max(0.1, Math.min(1.0, this.sneakSpeedAttr));
      int epf = Math.min(20, 3 * this.featherFalling + this.protection);
      this.fallMult = 1.0 - epf / 25.0;
      double sf = this.effSpeed;
      this.walkCost = 4.63 / sf;
      this.sprintCost = 3.56 / sf;
      this.sprintJumpCost = 2.95 / sf;
      this.waterRunCost = this.sprintCost * 0.92;
      double swimF = this.waterEff > 0.0 ? 1.0 + this.waterEff : 1.0 + 0.35 * this.depthStrider;
      swimF *= (this.dolphinsGrace ? 2.0 : 1.0) * (this.conduitPower ? 1.2 : 1.0);
      this.swimCost = Math.max(1.4, 6.0 / swimF);
      this.lavaCost = 14.0;
      this.flyCost = 2.1 / Math.max(0.2, this.flySpeed / 0.05);
   }

   public static PlayerPhysics of(Player p, AmberConfig cfg) {
      return new PlayerPhysics(p, cfg);
   }

   public double effectiveStep() {
      return this.sbAutoStep ? Math.max(this.stepHeight, 1.0) : this.stepHeight;
   }

   private static int amp(Player p, Holder<MobEffect> e) {
      MobEffectInstance i = p.getEffect(e);
      return i == null ? 0 : i.getAmplifier() + 1;
   }

   private static double attr(Player p, Holder<Attribute> a, double def) {
      try {
         double v = p.getAttributeValue(a);
         return Double.isFinite(v) && v > 0.0 ? v : def;
      } catch (Throwable var6) {
         return def;
      }
   }

   private static double attrZero(Player p, Holder<Attribute> a, double def) {
      try {
         double v = p.getAttributeValue(a);
         return Double.isFinite(v) && v >= 0.0 ? v : def;
      } catch (Throwable var6) {
         return def;
      }
   }

   private static int ench(Registry<Enchantment> reg, Player p, ResourceKey<Enchantment> key) {
      try {
         return EnchantmentHelper.getEnchantmentLevel(reg.getOrThrow(key), p);
      } catch (Throwable var4) {
         return 0;
      }
   }

   private static int clampInt(int v, int lo, int hi) {
      return Math.max(lo, Math.min(hi, v));
   }

   private static double[] simulateJump(double v0, double g) {
      double y = 0.0;
      double vy = v0;
      double peak = 0.0;
      double prevY = 0.0;
      double tFlat = -1.0;
      double tDown = -1.0;

      for (int t = 1; t <= 400; t++) {
         prevY = y;
         y += vy;
         if (y > peak) {
            peak = y;
         }

         if (tFlat < 0.0 && y <= 0.0 && vy < 0.0) {
            tFlat = t - 1 + frac(prevY, y);
         }

         if (tDown < 0.0 && y <= -1.0 && vy < 0.0) {
            tDown = t - 1 + frac(prevY + 1.0, y + 1.0);
         }

         vy = (vy - g) * 0.98;
         if (tFlat > 0.0 && tDown > 0.0 || y < -80.0) {
            break;
         }
      }

      if (tFlat < 0.0) {
         tFlat = 12.0;
      }

      if (tDown < 0.0) {
         tDown = tFlat + 3.0;
      }

      return new double[]{peak, tFlat, tDown};
   }

   private static double frac(double a, double b) {
      double d = a - b;
      return d <= 1.0E-9 ? 1.0 : Math.max(0.0, Math.min(1.0, a / d));
   }

   private static double[] simulateDoubleJump(double v0, double g) {
      double y = 0.0;
      double vy = v0;
      double peak = 0.0;
      double prevY = 0.0;
      boolean second = false;

      for (int t = 1; t <= 600; t++) {
         prevY = y;
         y += vy;
         if (y > peak) {
            peak = y;
         }

         if (!second && vy <= 0.0) {
            vy = v0;
            second = true;
         } else if (second && y <= 0.0 && vy < 0.0) {
            return new double[]{peak, t - 1 + frac(prevY, y)};
         }

         vy = (vy - g) * 0.98;
         if (y < -80.0) {
            break;
         }
      }

      return new double[]{peak, 18.0};
   }

   public double fallDamage(double drop) {
      if (!this.slowFalling && !this.creative && !this.spectator && !(this.fallDamageMult <= 0.0)) {
         double raw = Math.max(0.0, Math.ceil((drop - this.safeFall) * this.fallDamageMult));
         return raw * this.fallMult;
      } else {
         return 0.0;
      }
   }

   public double maxSafeDrop(double keepHearts) {
      if (!this.slowFalling && !this.creative && !this.spectator && !(this.fallDamageMult <= 0.0)) {
         double budget = this.health + this.absorption - 2.0 * keepHearts;
         if (budget <= 0.0) {
            return this.safeFall;
         } else if (this.fallMult <= 0.001) {
            return 256.0;
         } else {
            double raw = Math.floor((budget - 0.01) / (this.fallMult * this.fallDamageMult));
            return Math.min(256.0, this.safeFall + Math.max(0.0, raw));
         }
      } else {
         return 256.0;
      }
   }

   public static double landingMult(boolean safeReset, boolean hayOrHoney, boolean dripstone) {
      if (safeReset) {
         return 0.0;
      } else if (dripstone) {
         return 2.0;
      } else {
         return hayOrHoney ? 0.2 : 1.0;
      }
   }
}
