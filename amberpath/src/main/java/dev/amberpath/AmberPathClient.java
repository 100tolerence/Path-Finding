package dev.amberpath;

import dev.amberpath.command.GoCommand;
import dev.amberpath.config.AmberConfig;
import dev.amberpath.hud.AmberHud;
import dev.amberpath.path.Navigator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AmberPath — navigation automatique pilotée UNIQUEMENT par la commande /go.
 * Aucun menu, aucune touche : /go <x> [y] <z> lance le trajet, /go stop l'arrête.
 * Toute interférence manuelle (inventaire, chat, clavier, souris) stoppe la navigation.
 */
public class AmberPathClient implements ClientModInitializer {
	public static final String MOD_ID = "amberpath";
	public static final Logger LOGGER = LoggerFactory.getLogger("AmberPath");

	public static AmberConfig CONFIG;
	public static Navigator NAV;

	@Override
	public void onInitializeClient() {
		CONFIG = AmberConfig.load();
		NAV = new Navigator(CONFIG);

		GoCommand.register();

		ClientTickEvents.START_CLIENT_TICK.register(mc -> NAV.tickStart());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> NAV.tickEnd());

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), new AmberHud());
		LOGGER.info("[AmberPath] Prêt — /go <x> [y] <z> : naviguer, /go stop : arrêter.");
	}
}
