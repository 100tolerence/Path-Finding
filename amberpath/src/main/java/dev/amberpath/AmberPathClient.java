package dev.amberpath;

import dev.amberpath.config.AmberConfig;
import dev.amberpath.gui.AmberPathScreen;
import dev.amberpath.hud.AmberHud;
import dev.amberpath.path.Navigator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AmberPathClient implements ClientModInitializer {
	public static final String MOD_ID = "amberpath";
	public static final Logger LOGGER = LoggerFactory.getLogger("AmberPath");

	public static AmberConfig CONFIG;
	public static Navigator NAV;
	public static KeyMapping OPEN_KEY;
	public static KeyMapping TOGGLE_KEY;

	@Override
	public void onInitializeClient() {
		CONFIG = AmberConfig.load();
		NAV = new Navigator(CONFIG);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		OPEN_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.amberpath.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_G, category));
		TOGGLE_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.amberpath.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_V, category));

		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			handleHotkeys(mc);
			NAV.tickStart();
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> NAV.tickEnd());

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), new AmberHud());
		LOGGER.info("[AmberPath] Prêt — G : interface, V : activer/arrêter (touches modifiables).");
	}

	private void handleHotkeys(Minecraft mc) {
		boolean noScreen = mc.gui.screen() == null;
		boolean ourScreen = mc.gui.screen() instanceof AmberPathScreen;
		while (OPEN_KEY.consumeClick()) {
			if (noScreen) mc.setScreenAndShow(new AmberPathScreen());
		}
		while (TOGGLE_KEY.consumeClick()) {
			if (noScreen || ourScreen) NAV.toggle();
		}
	}
}
