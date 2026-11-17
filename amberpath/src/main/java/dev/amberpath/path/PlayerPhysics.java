package dev.amberpath.path;

import dev.amberpath.config.AmberConfig;
import dev.amberpath.skyblock.SkyblockHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * Profil physique LIVE du joueur : lit les attributs réels (vitesse, saut, gravité,
 * hauteur de pas, chute sûre, multiplicateur de dégâts de chute, efficacité aquatique,
 * vitesse accroupie) — ce qui capture automatiquement TOUS les effets (Speed même
 * niveau 255, Jump Boost, Slowness, Slow Falling, Dolphins Grace…) — plus les
 * enchantements (Depth Strider, Soul Speed, Feather Falling, Protection, Swift Sneak,
 * Frost Walker) et les bottes en cuir (neige poudreuse).
 *
 * Couche Hypixel Skyblock : la stat ✦ Speed lue dans le tab est combinée à l'attribut
 * vanilla pour déduire l'auto-step serveur (monter un bloc entier à haute vitesse) et
 * la course sur l'eau (≈ ✦ 400+). Simule la physique de saut vanilla pour en déduire
 * portée des sauts, hauteur de montée et budget de chute.
 */
public final class PlayerPhysics {
	// --- Effets / état ---
	public final boolean creative, spectator, flying, elytra, sleeping;
	public final boolean fireResist, slowFalling, levitation, dolphinsGrace, conduitPower, waterBreathing;
	public final int speedAmp, slownessAmp, jumpAmp;          // amplificateur + 1 (0 = absent)
	public final boolean canSprint;
	public final boolean leatherBoots;

	// --- Enchantements ---
	public final int depthStrider, soulSpeed, featherFalling, protection, swiftSneak, frostWalker;

	// --- Attributs live ---
	public final double moveSpeed, flySpeed, jumpStrength, gravity, safeFall, stepHeight;
	public final double fallDamageMult, waterEff, sneakSpeedAttr;
	public final double speedFactor;                          // moveSpeed / 0.1 (1 = normal)

	// --- Skyblock ---
	public final boolean sbActive;                            // Hypixel détecté + option active
	public final int sbSpeedStat;                             // ✦ Speed lue dans le tab (0 = inconnue)
	public final double effSpeed;                             // facteur effectif (attribut ↔ tab)
	public final boolean sbAutoStep;                          // montée auto des blocs entiers
	public final boolean sbWaterWalk;                         // course sur l'eau
	public final double waterRunCost;

	// --- Santé ---
	public final double health, absorption, maxHealth;

	// --- Capacités dérivées (sauts) ---
	public final double jumpPeak;                             // hauteur max du saut (blocs)
	public final double airTimeFlat, airTimeDown1;            // ticks en l'air (plat / -1)
	public final double sprintJumpDist, walkJumpDist;         // portée horizontale sprint-saut / saut simple
	public final int maxGapFlat, maxGapDown, maxAscend;       // écart max plat / descendant, montée max
	public final double sneakFactor;                          // vitesse accroupi (0.3 normal)
	public final double fallMult;                             // multiplicateur dégâts de chute (EPF)

	// --- Coûts calibrés (ticks / bloc) ---
	public final double walkCost, sprintCost, sprintJumpCost, swimCost, lavaCost, flyCost;

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
		this.canSprint = p.getFoodData().getFoodLevel() > 6 || creative;
		this.leatherBoots = p.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS);

		Registry<Enchantment> reg = p.registryAccess().<Enchantment>lookupOrThrow(Registries.ENCHANTMENT);
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
		this.speedFactor = Math.max(0.05, moveSpeed / 0.1);

		// --- Couche Skyblock ---
		Minecraft mc = Minecraft.getInstance();
		this.sbActive = SkyblockHelper.active(mc, cfg);
		this.sbSpeedStat = sbActive && cfg.sbUseTabSpeed ? SkyblockHelper.speedStat() : 0;
		double tabFactor = sbSpeedStat > 0 ? sbSpeedStat / 100.0 : 0;
		this.effSpeed = Math.max(speedFactor, tabFactor);
		this.sbAutoStep = sbActive && cfg.sbAutoStep && (cfg.sbForceStep || effSpeed * 100 >= cfg.sbStepSpeed);
		this.sbWaterWalk = sbActive && cfg.sbWaterWalk && (cfg.sbForceWater || effSpeed * 100 >= cfg.sbWaterSpeed);

		this.health = p.getHealth();
		this.absorption = p.getAbsorptionAmount();
		this.maxHealth = p.getMaxHealth();

		// Simulation verticale vanilla : y += vy ; vy = (vy - g) * 0.98
		double g = slowFalling ? 0.01 : gravity;
		double[] sim = simulateJump(jumpStrength, g);
		this.jumpPeak = sim[0];
		this.airTimeFlat = sim[1];
		this.airTimeDown1 = sim[2];

		// Portée horizontale calibrée : sprint-saut de base ≈ 4.2 blocs (écart 3).
		double vSprint = 0.36 * effSpeed;
		double vWalk = 0.17 * effSpeed;
		this.sprintJumpDist = vSprint * airTimeFlat;
		this.walkJumpDist = vWalk * airTimeFlat;
		int cap = 24; // borne de recherche (évite l'explosion combinatoire à Speed 255)
		this.maxGapFlat = clampInt((int) Math.floor(sprintJumpDist - 0.25) - 1, 1, cap);
		this.maxGapDown = clampInt((int) Math.floor(vSprint * airTimeDown1 - 0.25) - 1, 1, cap + 4);
		this.maxAscend = clampInt((int) Math.floor(jumpPeak - 0.05), 1, 8);

		this.sneakFactor = Math.max(0.1, Math.min(1.0, sneakSpeedAttr));

		// EPF chute : Feather Falling = 3/niveau, Protection = 1/niveau (plafond 20 → -80 %).
		int epf = Math.min(20, 3 * featherFalling + protection);
		this.fallMult = 1.0 - epf / 25.0;

		double sf = effSpeed;
		this.walkCost = 4.63 / sf;
		this.sprintCost = 3.56 / sf;
		this.sprintJumpCost = 2.95 / sf;
		this.waterRunCost = sprintCost * 0.92;
		double swimF = waterEff > 0 ? (1 + waterEff) : (1 + 0.35 * depthStrider);
		swimF *= (dolphinsGrace ? 2.0 : 1) * (conduitPower ? 1.2 : 1);
		this.swimCost = Math.max(1.4, 6.0 / swimF);
		this.lavaCost = 14.0;
		this.flyCost = 2.1 / Math.max(0.2, flySpeed / 0.05);
	}

	public static PlayerPhysics of(Player p, AmberConfig cfg) { return new PlayerPhysics(p, cfg); }

	/** Hauteur de pas effective (auto-step Skyblock = 1.0). */
	public double effectiveStep() { return sbAutoStep ? Math.max(stepHeight, 1.0) : stepHeight; }

	private static int amp(Player p, Holder<net.minecraft.world.effect.MobEffect> e) {
		MobEffectInstance i = p.getEffect(e);
		return i == null ? 0 : i.getAmplifier() + 1;
	}
	private static double attr(Player p, Holder<net.minecraft.world.entity.ai.attributes.Attribute> a, double def) {
		try { double v = p.getAttributeValue(a); return Double.isFinite(v) && v > 0 ? v : def; }
		catch (Throwable t) { return def; }
	}
	private static double attrZero(Player p, Holder<net.minecraft.world.entity.ai.attributes.Attribute> a, double def) {
		try { double v = p.getAttributeValue(a); return Double.isFinite(v) && v >= 0 ? v : def; }
		catch (Throwable t) { return def; }
	}
	private static int ench(Registry<Enchantment> reg, Player p, net.minecraft.resources.ResourceKey<Enchantment> key) {
		try { return EnchantmentHelper.getEnchantmentLevel(reg.getOrThrow(key), p); }
		catch (Throwable t) { return 0; }
	}
	private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

	/** {pic, tempsPlat, temps-1} — simulation ticks vanilla (atterrissage interpolé). */
	private static double[] simulateJump(double v0, double g) {
		double y = 0, vy = v0, peak = 0, prevY = 0;
		double tFlat = -1, tDown = -1;
		for (int t = 1; t <= 400; t++) {
			prevY = y;
			y += vy;
			if (y > peak) peak = y;
			if (tFlat < 0 && y <= 0 && vy < 0) tFlat = t - 1 + frac(prevY, y);
			if (tDown < 0 && y <= -1 && vy < 0) tDown = t - 1 + frac(prevY + 1, y + 1);
			vy = (vy - g) * 0.98;
			if (tFlat > 0 && tDown > 0) break;
			if (y < -80) break;
		}
		if (tFlat < 0) tFlat = 12;
		if (tDown < 0) tDown = tFlat + 3;
		return new double[]{peak, tFlat, tDown};
	}
	private static double frac(double a, double b) {
		double d = a - b;
		return d <= 1e-9 ? 1 : Math.max(0, Math.min(1, a / d));
	}

	/** Dégâts de chute bruts (hors modificateur d'atterrissage) pour une hauteur donnée. */
	public double fallDamage(double drop) {
		if (slowFalling || creative || spectator || fallDamageMult <= 0) return 0;
		double raw = Math.max(0, Math.ceil((drop - safeFall) * fallDamageMult));
		return raw * fallMult;
	}

	/** Hauteur de chute max en gardant `keepHearts` cœurs (budget santé live). */
	public double maxSafeDrop(double keepHearts) {
		if (slowFalling || creative || spectator || fallDamageMult <= 0) return 256;
		double budget = health + absorption - 2.0 * keepHearts;
		if (budget <= 0) return safeFall;
		if (fallMult <= 0.001) return 256;
		double raw = Math.floor((budget - 0.01) / (fallMult * fallDamageMult));
		return Math.min(256, safeFall + Math.max(0, raw));
	}

	/** Modificateur de dégâts selon le bloc d'atterrissage (foin/miel -80 %, slime/toile/eau 0…). */
	public static double landingMult(boolean safeReset, boolean hayOrHoney, boolean dripstone) {
		if (safeReset) return 0;
		if (dripstone) return 2.0;
		if (hayOrHoney) return 0.2;
		return 1.0;
	}

	/** Pastilles d'effets/enchantements détectés pour l'interface. */
	public List<String> chips() {
		List<String> l = new ArrayList<>();
		if (sbSpeedStat > 0) l.add("✦ Speed " + sbSpeedStat);
		if (sbAutoStep) l.add("Auto-step ✓");
		if (sbWaterWalk) l.add("Course sur l'eau ✓");
		if (speedAmp > 0) l.add("Speed " + roman(speedAmp));
		if (slownessAmp > 0) l.add("Slowness " + roman(slownessAmp));
		if (jumpAmp > 0) l.add("Jump Boost " + roman(jumpAmp));
		if (fireResist) l.add("Fire Resistance");
		if (slowFalling) l.add("Slow Falling");
		if (dolphinsGrace) l.add("Dolphins Grace");
		if (conduitPower) l.add("Conduit Power");
		if (waterBreathing) l.add("Water Breathing");
		if (levitation) l.add("Levitation");
		if (depthStrider > 0) l.add("Depth Strider " + roman(depthStrider));
		if (soulSpeed > 0) l.add("Soul Speed " + roman(soulSpeed));
		if (featherFalling > 0) l.add("Feather Falling " + roman(featherFalling));
		if (protection > 0) l.add("Protection " + roman(protection));
		if (swiftSneak > 0) l.add("Swift Sneak " + roman(swiftSneak));
		if (frostWalker > 0) l.add("Frost Walker " + roman(frostWalker));
		if (leatherBoots) l.add("Bottes en cuir");
		if (flying) l.add("Vol créatif");
		if (spectator) l.add("Spectateur");
		return l;
	}

	private static String roman(int n) {
		return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V";
			case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII"; case 9 -> "IX"; case 10 -> "X"; default -> String.valueOf(n); };
	}
}
