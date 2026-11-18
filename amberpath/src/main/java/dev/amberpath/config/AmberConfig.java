package dev.amberpath.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;

public class AmberConfig {
   public boolean sprint = true;
   public boolean parkour = true;
   public int maxGap = 3;
   public boolean diagonals = true;
   public boolean swim = true;
   public boolean climb = true;
   public boolean doors = true;
   public boolean descend = true;
   public int maxFall = 3;
   public boolean riskyFalls = true;
   public double keepHearts = 1.0;
   public boolean adaptiveParkour = true;
   public boolean doubleJump = true;
   public boolean iceParkour = true;
   public boolean sneakMagma = true;
   public boolean avoidDanger = true;
   public boolean flyAuto = true;
   public double weight = 1.0;
   public int maxNodes = 500000;
   public int budgetMs = 8;
   public int partialAfterMs = 60;
   public int cfgVersion = 0;
   public boolean autoReplan = true;
   public boolean smoothPath = true;
   public boolean pauseOnDamage = false;
   public boolean showPath = true;
   public boolean targetSet = false;
   public int targetX = 0;
   public Integer targetY = null;
   public int targetZ = 0;
   public boolean smoothRotation = true;
   public boolean skyblockAuto = true;
   public boolean sbUseTabSpeed = true;
   public boolean sbAutoStep = true;
   public int sbStepSpeed = 300;
   public boolean sbForceStep = false;
   public boolean sbWaterWalk = true;
   public int sbWaterSpeed = 400;
   public boolean sbForceWater = false;
   public boolean frostWalkerWalk = true;
   public boolean voidGuard = true;
   public int maxFallScan = 48;
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

   private static Path file() {
      return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("amberpath.json");
   }

   public static AmberConfig load() {
      try {
         Path p = file();
         if (Files.exists(p)) {
            AmberConfig c = (AmberConfig)GSON.fromJson(Files.readString(p), AmberConfig.class);
            if (c != null) {
               c.enforceNavigationPolicy();
               if (c.cfgVersion < 2) {
                  c.doubleJump = true;
                  c.cfgVersion = 2;
                  c.save();
               }

               return c;
            }
         }
      } catch (Exception var2) {
      }

      AmberConfig defaults = new AmberConfig();
      defaults.enforceNavigationPolicy();
      return defaults;
   }

   public void enforceNavigationPolicy() {
      this.weight = 1.0;
      this.maxNodes = 500000;
      this.budgetMs = 8;
      this.partialAfterMs = 60;
      this.sprint = true;
      this.parkour = true;
      this.maxGap = 3;
      this.diagonals = true;
      this.swim = true;
      this.climb = true;
      this.doors = true;
      this.descend = true;
      this.adaptiveParkour = true;
      this.iceParkour = true;
      this.sneakMagma = true;
      this.avoidDanger = true;
      this.flyAuto = true;
      this.smoothPath = true;
      this.smoothRotation = true;
      this.autoReplan = true;
      this.riskyFalls = false;
   }

   public void save() {
      this.enforceNavigationPolicy();

      try {
         Path p = file();
         Files.createDirectories(p.getParent());
         Files.writeString(p, GSON.toJson(this));
      } catch (Exception var2) {
      }
   }
}
