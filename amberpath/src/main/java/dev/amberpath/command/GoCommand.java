package dev.amberpath.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.amberpath.AmberPathClient;
import dev.amberpath.config.AmberConfig;
import dev.amberpath.path.Navigator;
import dev.amberpath.path.PathEngine;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

public final class GoCommand {
   private GoCommand() {
   }

   public static void register() {
      ClientCommandRegistrationCallback.EVENT
         .register(
            (ClientCommandRegistrationCallback)(dispatcher, buildContext) -> {
               LiteralArgumentBuilder<FabricClientCommandSource> go = (LiteralArgumentBuilder<FabricClientCommandSource>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommands.literal(
                           "go"
                        )
                        .executes(c -> goStored((FabricClientCommandSource)c.getSource())))
                     .then(ClientCommands.literal("help").executes(c -> help((FabricClientCommandSource)c.getSource()))))
                  .then(ClientCommands.literal("stop").executes(c -> stop((FabricClientCommandSource)c.getSource())));
               go.then(
                  ((LiteralArgumentBuilder)ClientCommands.literal("set").executes(c -> showStored((FabricClientCommandSource)c.getSource())))
                     .then(coordArgs(true))
               );
               go.then(coordArgs(false));
               dispatcher.register(go);
            }
         );
   }

   private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> coordArgs(boolean store) {
      RequiredArgumentBuilder<FabricClientCommandSource, Integer> x = ClientCommands.argument("x", IntegerArgumentType.integer()).suggests((c, b) -> {
         LocalPlayer p = ((FabricClientCommandSource)c.getSource()).getPlayer();
         if (p != null) {
            b.suggest(p.getBlockX());
         }

         return b.buildFuture();
      });
      x.then(
         ClientCommands.argument("z", IntegerArgumentType.integer())
            .suggests((c, b) -> {
               LocalPlayer p = ((FabricClientCommandSource)c.getSource()).getPlayer();
               if (p != null) {
                  b.suggest(p.getBlockZ());
               }

               return b.buildFuture();
            })
            .executes(
               c -> run(
                  (FabricClientCommandSource)c.getSource(), (Integer)c.getArgument("x", Integer.class), null, (Integer)c.getArgument("z", Integer.class), store
               )
            )
      );
      x.then(
         ClientCommands.argument("y", IntegerArgumentType.integer())
            .suggests((c, b) -> {
               LocalPlayer p = ((FabricClientCommandSource)c.getSource()).getPlayer();
               if (p != null) {
                  b.suggest(p.getBlockY());
               }

               return b.buildFuture();
            })
            .then(
               ClientCommands.argument("z", IntegerArgumentType.integer())
                  .suggests((c, b) -> {
                     LocalPlayer p = ((FabricClientCommandSource)c.getSource()).getPlayer();
                     if (p != null) {
                        b.suggest(p.getBlockZ());
                     }

                     return b.buildFuture();
                  })
                  .executes(
                     c -> run(
                        (FabricClientCommandSource)c.getSource(),
                        (Integer)c.getArgument("x", Integer.class),
                        (Integer)c.getArgument("y", Integer.class),
                        (Integer)c.getArgument("z", Integer.class),
                        store
                     )
                  )
            )
      );
      return x;
   }

   private static int run(FabricClientCommandSource src, int x, Integer y, int z, boolean store) {
      return store ? set(src, x, y, z) : navigate(src, x, y, z);
   }

   private static int set(FabricClientCommandSource src, int x, Integer y, int z) {
      if (y == null || src.getLevel() == null || y >= src.getLevel().getMinY() && y < src.getLevel().getMaxY()) {
         AmberConfig cfg = AmberPathClient.CONFIG;
         cfg.targetSet = true;
         cfg.targetX = x;
         cfg.targetY = y;
         cfg.targetZ = z;
         cfg.save();
         src.sendFeedback(
            Component.literal(
               "§6[AmberPath] §rCible enregistrée : §e" + x + ", " + (y == null ? "auto" : y) + ", " + z + "§r — §7/go§r pour lancer la navigation."
            )
         );
         return 1;
      } else {
         src.sendError(Component.literal("AmberPath : y=" + y + " hors des limites du monde."));
         return 0;
      }
   }

   private static int showStored(FabricClientCommandSource src) {
      AmberConfig cfg = AmberPathClient.CONFIG;
      if (cfg != null && cfg.targetSet) {
         src.sendFeedback(
            Component.literal(
               "§6[AmberPath] §rCible enregistrée : §e"
                  + cfg.targetX
                  + ", "
                  + (cfg.targetY == null ? "auto" : cfg.targetY)
                  + ", "
                  + cfg.targetZ
                  + "§r — §7/go§r pour y naviguer, §7/go stop§r pour l'effacer."
            )
         );
      } else {
         src.sendFeedback(Component.literal("§6[AmberPath] §rAucune cible enregistrée — §7/go set <x> <y> <z>§r."));
      }

      return 1;
   }

   private static int goStored(FabricClientCommandSource src) {
      AmberConfig cfg = AmberPathClient.CONFIG;
      if (cfg != null && cfg.targetSet) {
         return navigate(src, cfg.targetX, cfg.targetY, cfg.targetZ);
      } else {
         src.sendFeedback(Component.literal("§6[AmberPath] §rAucune cible enregistrée — utilisez §e/go set <x> <y> <z>§r d'abord."));
         return help(src);
      }
   }

   private static int navigate(FabricClientCommandSource src, int x, Integer y, int z) {
      Navigator nav = AmberPathClient.NAV;
      LocalPlayer p = src.getPlayer();
      if (nav != null && p != null && src.getLevel() != null) {
         if (y == null || y >= src.getLevel().getMinY() && y < src.getLevel().getMaxY()) {
            nav.start(new PathEngine.Goal(0, x, y, z, 0));
            return 1;
         } else {
            src.sendError(Component.literal("AmberPath : y=" + y + " hors des limites du monde."));
            return 0;
         }
      } else {
         src.sendError(Component.literal("AmberPath : pas de monde/joueur actif."));
         return 0;
      }
   }

   private static int stop(FabricClientCommandSource src) {
      Navigator nav = AmberPathClient.NAV;
      AmberConfig cfg = AmberPathClient.CONFIG;
      boolean hadTarget = cfg != null && cfg.targetSet;
      if (cfg != null && hadTarget) {
         cfg.targetSet = false;
         cfg.targetY = null;
         cfg.save();
      }

      if (nav == null) {
         return 0;
      } else {
         if (nav.active()) {
            nav.stop();
            src.sendFeedback(Component.literal("§6[AmberPath] §rNavigation §carrêtée§r" + (hadTarget ? " et cible §csupprimée§r." : ".")));
         } else if (hadTarget) {
            src.sendFeedback(Component.literal("§6[AmberPath] §rCible enregistrée §csupprimée§r (aucune navigation en cours)."));
         } else {
            src.sendFeedback(Component.literal("§6[AmberPath] §rAucune navigation en cours, aucune cible enregistrée."));
         }

         return 1;
      }
   }

   private static int help(FabricClientCommandSource src) {
      src.sendFeedback(Component.literal("§6[AmberPath] §e/go set <x> <y> <z>§r — enregistre la cible (§ey§r facultatif : hauteur auto)."));
      src.sendFeedback(Component.literal("§6[AmberPath] §e/go§r — lance la navigation vers la cible enregistrée."));
      src.sendFeedback(Component.literal("§6[AmberPath] §e/go <x> <y> <z>§r — navigation directe (§ey§r facultatif)."));
      src.sendFeedback(Component.literal("§6[AmberPath] §e/go stop§r — arrête la navigation et supprime la cible."));
      src.sendFeedback(Component.literal("§6[AmberPath] §e/go set§r / §ego help§r — cible actuelle / aide."));
      Navigator nav = AmberPathClient.NAV;
      if (nav != null && nav.active() && nav.goal != null) {
         src.sendFeedback(
            Component.literal("§6[AmberPath] §rEn cours vers §e" + nav.goal.x + ", " + (nav.goal.y == null ? "auto" : nav.goal.y) + ", " + nav.goal.z + "§r.")
         );
      }

      return 1;
   }
}
