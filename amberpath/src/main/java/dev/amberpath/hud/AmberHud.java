package dev.amberpath.hud;

import dev.amberpath.AmberPathClient;
import dev.amberpath.path.Navigator;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class AmberHud implements HudElement {
   public void extractRenderState(GuiGraphicsExtractor ex, DeltaTracker dt) {
      Navigator nav = AmberPathClient.NAV;
      if (nav != null) {
         nav.frameAim();
      }
   }
}
