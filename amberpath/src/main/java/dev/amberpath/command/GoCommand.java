package dev.amberpath.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.amberpath.AmberPathClient;
import dev.amberpath.path.Navigator;
import dev.amberpath.path.PathEngine;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Unique point d'entrée d'AmberPath : la commande client /go.
 *
 *   /go <x> <z>        → navigue jusqu'au bloc (hauteur automatique)
 *   /go <x> <y> <z>    → navigue jusqu'au bloc exact
 *   /go stop           → arrête la navigation en cours
 *   /go                → aide + état courant
 *
 * Aucun menu, aucune touche. La navigation s'arrête aussi d'elle-même dès que
 * le joueur reprend la main (inventaire, chat, déplacement ou caméra manuels).
 */
public final class GoCommand {
	private GoCommand() {}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			LiteralArgumentBuilder<FabricClientCommandSource> go = ClientCommands.literal("go")
					.executes(c -> usage(c.getSource()))
					.then(ClientCommands.literal("stop")
							.executes(c -> stop(c.getSource())));

			RequiredArgumentBuilder<FabricClientCommandSource, Integer> x =
					ClientCommands.argument("x", IntegerArgumentType.integer())
							.suggests((c, b) -> {
								LocalPlayer p = c.getSource().getPlayer();
								if (p != null) b.suggest(p.getBlockX());
								return b.buildFuture();
							});

			// /go <x> <z> — hauteur auto
			x.then(ClientCommands.argument("z", IntegerArgumentType.integer())
					.suggests((c, b) -> {
						LocalPlayer p = c.getSource().getPlayer();
						if (p != null) b.suggest(p.getBlockZ());
						return b.buildFuture();
					})
					.executes(c -> go(c.getSource(),
							c.getArgument("x", Integer.class), null, c.getArgument("z", Integer.class))));

			// /go <x> <y> <z> — hauteur exacte
			x.then(ClientCommands.argument("y", IntegerArgumentType.integer())
					.suggests((c, b) -> {
						LocalPlayer p = c.getSource().getPlayer();
						if (p != null) b.suggest(p.getBlockY());
						return b.buildFuture();
					})
					.then(ClientCommands.argument("z", IntegerArgumentType.integer())
							.suggests((c, b) -> {
								LocalPlayer p = c.getSource().getPlayer();
								if (p != null) b.suggest(p.getBlockZ());
								return b.buildFuture();
							})
							.executes(c -> go(c.getSource(),
									c.getArgument("x", Integer.class),
									c.getArgument("y", Integer.class),
									c.getArgument("z", Integer.class)))));

			dispatcher.register(go);
		});
	}

	private static int go(FabricClientCommandSource src, int x, Integer y, int z) {
		Navigator nav = AmberPathClient.NAV;
		LocalPlayer p = src.getPlayer();
		if (nav == null || p == null || src.getLevel() == null) {
			src.sendError(Component.literal("AmberPath : pas de monde/joueur actif."));
			return 0;
		}
		if (y != null && (y < src.getLevel().getMinY() || y >= src.getLevel().getMaxY())) {
			src.sendError(Component.literal("AmberPath : y=" + y + " hors des limites du monde."));
			return 0;
		}
		nav.start(new PathEngine.Goal(0, x, y, z, 0));
		return 1;
	}

	private static int stop(FabricClientCommandSource src) {
		Navigator nav = AmberPathClient.NAV;
		if (nav == null) return 0;
		if (nav.active()) {
			nav.stop();
			src.sendFeedback(Component.literal("§6[AmberPath] §rNavigation §carrêtée§r."));
		} else {
			src.sendFeedback(Component.literal("§6[AmberPath] §rAucune navigation en cours."));
		}
		return 1;
	}

	private static int usage(FabricClientCommandSource src) {
		Navigator nav = AmberPathClient.NAV;
		src.sendFeedback(Component.literal("§6[AmberPath] §r/go <x> <z> ou /go <x> <y> <z> — /go stop pour arrêter."));
		if (nav != null && nav.active() && nav.goal != null) {
			src.sendFeedback(Component.literal("§6[AmberPath] §rEn cours vers §e"
					+ nav.goal.x + ", " + (nav.goal.y == null ? "auto" : nav.goal.y) + ", " + nav.goal.z + "§r."));
		}
		return 1;
	}
}
