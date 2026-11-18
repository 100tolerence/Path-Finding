package dev.amberpath.skyblock;

import dev.amberpath.config.AmberConfig;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

public final class SkyblockHelper {
   private static final Pattern SPEED = Pattern.compile("Speed[^0-9]{0,6}(\\d{1,4})");
   private static final Pattern CODES = Pattern.compile("§.");
   private static String lastIp = "\u0000";
   private static boolean hypixel;
   private static boolean skyblock;
   private static int tabSpeed;
   private static long tabAt;
   private static long sbAt;

   private SkyblockHelper() {
   }

   public static boolean isHypixel(Minecraft mc) {
      ServerData sd = mc.getCurrentServer();
      String ip = sd != null && sd.ip != null ? sd.ip.toLowerCase(Locale.ROOT) : "";
      if (!ip.equals(lastIp)) {
         lastIp = ip;
         hypixel = ip.contains("hypixel.net") || ip.contains("hypixel.io");
         tabSpeed = 0;
         skyblock = false;
         tabAt = 0L;
         sbAt = 0L;
      }

      return hypixel;
   }

   public static boolean active(Minecraft mc, AmberConfig cfg) {
      return cfg.skyblockAuto && isHypixel(mc);
   }

   public static boolean skyblock() {
      return skyblock;
   }

   public static int speedStat() {
      return tabSpeed;
   }

   public static void refresh(Minecraft mc, AmberConfig cfg) {
      if (active(mc, cfg) && mc.player != null && mc.level != null) {
         long now = System.currentTimeMillis();
         if (now - tabAt >= 1000L) {
            tabAt = now;

            try {
               for (PlayerInfo pi : mc.player.connection.getListedOnlinePlayers()) {
                  Component c = pi.getTabListDisplayName();
                  if (c != null) {
                     String s = CODES.matcher(c.getString()).replaceAll("");
                     Matcher m = SPEED.matcher(s);
                     if (m.find()) {
                        tabSpeed = Integer.parseInt(m.group(1));
                        break;
                     }
                  }
               }
            } catch (Throwable var10) {
            }
         }

         if (now - sbAt >= 2000L) {
            sbAt = now;

            try {
               Objective o = mc.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
               if (o != null) {
                  String t = CODES.matcher(o.getDisplayName().getString()).replaceAll("").toUpperCase(Locale.ROOT);
                  skyblock = t.contains("SKYBLOCK");
               } else {
                  skyblock = false;
               }
            } catch (Throwable var9) {
            }
         }
      } else {
         tabSpeed = 0;
         skyblock = false;
      }
   }
}
