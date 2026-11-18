package dev.amberpath.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Paramètres de navigation persistés dans config/amberpath.json.
 * Depuis la v2 : UNIQUEMENT la commande /go (aucun menu, aucune touche, aucun preset).
 * Les anciens fichiers sont migrés automatiquement (les champs GUI sont ignorés).
 */
public class AmberConfig {
	// Mouvement
	public boolean sprint = true;
	public boolean parkour = true;
	public int maxGap = 3;            // écart max des sauts parkour (blocs)
	public boolean diagonals = true;
	public boolean swim = true;
	public boolean climb = true;      // échelles, lianes, échafaudages
	public boolean doors = true;      // ouvrir portes & portillons en bois
	public boolean descend = true;
	public int maxFall = 3;           // chute sans dégâts maximale (blocs)
	public boolean riskyFalls = true; // autoriser les chutes avec dégâts (bornées par la santé)
	public double keepHearts = 1.0;   // cœurs minimum conservés après une chute risquée
	public boolean adaptiveParkour = true; // portée des sauts adaptée à Speed / Jump Boost / élan
	public boolean doubleJump = true; // 2e impulsion en l'air SI effets/vitesse le permettent (sinon jamais)
	public boolean iceParkour = true; // autoriser les sauts depuis la glace
	public boolean sneakMagma = true;
	public boolean avoidDanger = true;// lave, feu, cactus, wither rose, poudreuse
	public boolean flyAuto = true;    // mode vol créatif automatique

	// Recherche
	public double weight = 1.0;       // verrouillé : A* non pondéré, chemin au coût estimé minimal
	public int maxNodes = 500000;
	public int budgetMs = 8;          // temps de calcul par tick
	public int partialAfterMs = 60;   // premier segment rapide, puis calcul incrémental pendant le déplacement
	public int cfgVersion = 0;
	public boolean autoReplan = true;
	public boolean smoothPath = true; // lissage « string-pulling » des longs segments

	// Confort / sécurité
	public boolean pauseOnDamage = false; // les dégâts (chutes, mobs) n'interrompent pas le chemin
	public boolean showHud = true;
	public boolean showPath = true;
	public boolean smoothRotation = true;
	// Note : l'arrêt sur interférence manuelle (écran, clavier, souris) est TOUJOURS actif.

	// --- Hypixel Skyblock ---
	public boolean skyblockAuto = true;   // détection automatique de Hypixel Skyblock
	public boolean sbUseTabSpeed = true;  // lire la stat ✦ Speed dans la liste des joueurs (tab)
	public boolean sbAutoStep = true;     // montée automatique des blocs entiers à haute vitesse
	public int sbStepSpeed = 300;         // seuil ✦ Speed pour l'auto-step
	public boolean sbForceStep = false;   // forcer l'auto-step quel que soit la vitesse
	public boolean sbWaterWalk = true;    // courir SUR l'eau à très haute vitesse
	public int sbWaterSpeed = 400;        // seuil ✦ Speed pour la marche sur l'eau
	public boolean sbForceWater = false;  // forcer la marche sur l'eau quel que soit la vitesse
	public boolean frostWalkerWalk = true;// Semelles givrantes : l'eau gèle sous les pas
	public boolean voidGuard = true;      // aucune chute / aucun saut sans atterrissage confirmé
	public int maxFallScan = 48;          // profondeur max de recherche d'atterrissage (blocs)

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("amberpath.json");
	}

	public static AmberConfig load() {
		try {
			Path p = file();
			if (Files.exists(p)) {
				AmberConfig c = GSON.fromJson(Files.readString(p), AmberConfig.class);
				if (c != null) {
					c.enforceNavigationPolicy();
					if (c.cfgVersion < 2) { // migration v2 : /go uniquement, double jump conditionnel
						c.doubleJump = true;
						c.cfgVersion = 2;
						c.save();
					}
					return c;
				}
			}
		} catch (Exception ignored) {}
		AmberConfig defaults = new AmberConfig();
		defaults.enforceNavigationPolicy();
		return defaults;
	}

	/** Paramètres de navigation verrouillés : aucun ancien fichier ne peut dégrader le calcul. */
	public void enforceNavigationPolicy() {
		weight = 1.0; maxNodes = 500000; budgetMs = 8; partialAfterMs = 60;
		sprint = true; parkour = true; maxGap = 3; diagonals = true; swim = true; climb = true; doors = true;
		descend = true; adaptiveParkour = true; iceParkour = true; sneakMagma = true; avoidDanger = true;
		flyAuto = true; smoothPath = true; smoothRotation = true; autoReplan = true;
		// Les chutes dommageables restent interdites : priorité à un trajet rapide mais sûr.
		riskyFalls = false;
	}

	public void save() {
		enforceNavigationPolicy();
		try {
			Path p = file();
			Files.createDirectories(p.getParent());
			Files.writeString(p, GSON.toJson(this));
		} catch (Exception ignored) {}
	}
}
