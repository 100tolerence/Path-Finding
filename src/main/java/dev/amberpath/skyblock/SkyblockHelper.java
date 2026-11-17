package dev.amberpath.skyblock;

import dev.amberpath.config.AmberConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détection Hypixel Skyblock + lecture des statistiques du serveur.
 *
 * - IP du serveur → Hypixel (mc.hypixel.net, alpha, stuck…).
 * - Liste des joueurs (tab) → stat « ✦ Speed » réelle (100 = normal, 400 = ×4, 500 avec
 *   le cap Black Cat / Young Dragon). C'est cette stat qui déclenche côté serveur
 *   l'auto-step (monter les blocs entiers sans sauter) et la course SUR l'eau.
 * - Scoreboard latéral → titre « SKYBLOCK » (badge de l'interface).
 *
 * Tout est mis en cache et rafraîchi à bas coût (1–2 s), aucune allocation par tick.
 */
public final class SkyblockHelper {
	private static final Pattern SPEED = Pattern.compile("Speed[^0-9]{0,6}(\\d{1,4})");
	private static final Pattern CODES = Pattern.compile("§.");

	private static String lastIp = "\u0000";
	private static boolean hypixel;
	private static boolean skyblock;
	private static int tabSpeed;
	private static long tabAt, sbAt;

	private SkyblockHelper() {}

	public static boolean isHypixel(Minecraft mc) {
		ServerData sd = mc.getCurrentServer();
		String ip = sd == null || sd.ip == null ? "" : sd.ip.toLowerCase(Locale.ROOT);
		if (!ip.equals(lastIp)) {
			lastIp = ip;
			hypixel = ip.contains("hypixel.net") || ip.contains("hypixel.io");
			tabSpeed = 0;
			skyblock = false;
			tabAt = 0;
			sbAt = 0;
		}
		return hypixel;
	}

	/** Vrai si les fonctions Skyblock doivent s'appliquer (Hypixel + option active). */
	public static boolean active(Minecraft mc, AmberConfig cfg) {
		return cfg.skyblockAuto && isHypixel(mc);
	}

	public static boolean skyblock() { return skyblock; }
	public static int speedStat() { return tabSpeed; }

	/** Rafraîchit tab + scoreboard (appelé ~1×/s, sûr même hors Hypixel). */
	public static void refresh(Minecraft mc, AmberConfig cfg) {
		if (!active(mc, cfg) || mc.player == null || mc.level == null) { tabSpeed = 0; skyblock = false; return; }
		long now = System.currentTimeMillis();
		if (now - tabAt >= 1000) {
			tabAt = now;
			try {
				for (PlayerInfo pi : mc.player.connection.getListedOnlinePlayers()) {
					Component c = pi.getTabListDisplayName();
					if (c == null) continue;
					String s = CODES.matcher(c.getString()).replaceAll("");
					Matcher m = SPEED.matcher(s);
					if (m.find()) {
						tabSpeed = Integer.parseInt(m.group(1));
						break;
					}
				}
			} catch (Throwable ignored) {}
		}
		if (now - sbAt >= 2000) {
			sbAt = now;
			try {
				Objective o = mc.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
				if (o != null) {
					String t = CODES.matcher(o.getDisplayName().getString()).replaceAll("").toUpperCase(Locale.ROOT);
					skyblock = t.contains("SKYBLOCK");
				} else skyblock = false;
			} catch (Throwable ignored) {}
		}
	}
}
