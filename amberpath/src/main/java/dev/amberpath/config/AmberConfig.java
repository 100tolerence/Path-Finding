package dev.amberpath.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;

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
	public boolean riskyFalls = false;// autoriser les chutes avec dégâts
	public boolean sneakMagma = true;
	public boolean avoidDanger = true;// lave, feu, cactus, wither rose, poudreuse
	public boolean flyAuto = true;    // mode vol créatif automatique

	// Recherche
	public double weight = 1.15;      // 1.0 = optimal, 1.6 = rapide
	public int maxNodes = 160000;
	public int budgetMs = 5;          // temps de calcul par tick
	public boolean autoReplan = true;
	public int nearRadius = 3;

	// Confort / sécurité
	public boolean pauseInMenus = true;
	public boolean pauseOnDamage = true;
	public boolean showHud = true;
	public boolean showPath = true;
	public boolean smoothRotation = true;

	// Objectif
	public int goalMode = 0;          // 0 = se tenir dessus, 1 = atteindre, 2 = approcher
	public String lastX = "";
	public String lastY = "";
	public String lastZ = "";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("amberpath.json");
	}

	public static AmberConfig load() {
		try {
			Path p = file();
			if (Files.exists(p)) {
				AmberConfig c = GSON.fromJson(Files.readString(p), AmberConfig.class);
				if (c != null) return c;
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
