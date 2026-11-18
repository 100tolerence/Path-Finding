package dev.amberpath;

import dev.amberpath.command.GoCommand;
import dev.amberpath.config.AmberConfig;
import dev.amberpath.hud.AmberHud;
import dev.amberpath.path.Navigator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.StartTick;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AmberPathClient implements ClientModInitializer {
   public static final String MOD_ID = "amberpath";
   public static final String VERSION = "1.2.3";
   public static final Logger LOGGER = LoggerFactory.getLogger("AmberPath");
   public static AmberConfig CONFIG;
   public static Navigator NAV;
   private static int announceDelay = -1;

   public void onInitializeClient() {
      CONFIG = AmberConfig.load();
      NAV = new Navigator(CONFIG);
      GoCommand.register();
      ClientTickEvents.START_CLIENT_TICK.register((StartTick)mc -> NAV.tickStart());
      ClientTickEvents.END_CLIENT_TICK
         .register(
            (EndTick)mc -> {
               NAV.tickEnd();
               if (mc.player == null || mc.level == null) {
                  announceDelay = -1;
               } else if (announceDelay < 0) {
                  announceDelay = 30;
               } else if (announceDelay > 0 && --announceDelay == 0) {
                  mc.player
                     .sendSystemMessage(
                        Component.literal("§6[AmberPath] §rv1.2.3 chargé §a✓§r — §7/go help§r pour l'aide, §7/go set <x> <y> <z>§r puis §7/go§r.")
                     );
               }
            }
         );
      HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("amberpath", "hud"), new AmberHud());
      LOGGER.info("[AmberPath] v1.2.3 Prêt — /go set <x> [y] <z> puis /go : naviguer, /go stop : arrêter, /go help : aide.");
   }
}
