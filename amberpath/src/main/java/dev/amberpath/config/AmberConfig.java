package dev.amberpath.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Tous les paramètres de navigation, persistés dans config/amberpath.json. */
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
	public boolean adaptiveParkour = true; // portée des sauts adaptée à Speed / Jump Boost
	public boolean iceParkour = true; // autoriser les sauts depuis la glace
	public boolean sneakMagma = true;
	public boolean avoidDanger = true;// lave, feu, cactus, wither rose, poudreuse
	public boolean flyAuto = true;    // mode vol créatif automatique

	// Recherche
	public double weight = 1.15;      // 1.0 = optimal, 1.6 = rapide
	public int maxNodes = 160000;
	public int budgetMs = 5;          // temps de calcul par tick
	public boolean autoReplan = true;
	public int nearRadius = 3;
	public boolean smoothPath = true; // lissage « string-pulling » des longs segments

	// Confort / sécurité
	public boolean pauseInMenus = true;
	public boolean pauseOnDamage = false; // les dégâts (chutes, mobs) n'interrompent pas le chemin
	public boolean showHud = true;
	public boolean showPath = true;
	public boolean smoothRotation = true;

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
	public int maxFallScan = 96;          // profondeur max de recherche d'atterrissage (blocs)

	// Objectif
	public int goalMode = 0;          // 0 = se tenir dessus, 1 = atteindre, 2 = approcher
	public String lastX = "";
	public String lastY = "";
	public String lastZ = "";

	// Points de repère enregistrés (îles, hub, jardin…))
	public List<Preset> presets = new ArrayList<>();

	public static class Preset {
		public String name;
		public int x;
		public Integer y;
		public int z;
		public Preset() {}
		public Preset(String name, int x, Integer y, int z) { this.name = name; this.x = x; this.y = y; this.z = z; }
	}

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
					if (c.presets == null) c.presets = new ArrayList<>();
					return c;
				}
			}
		} catch (Exception ignored) {}
		return new AmberConfig();
	}

	public void save() {
		try {
			Path p = file();
			Files.createDirectories(p.getParent());
			Files.writeString(p, GSON.toJson(this));
		} catch (Exception ignored) {}
	}
}
