package dev.amberpath.gui;

import dev.amberpath.AmberPathClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/** Widgets Liquid Glass personnalisés. */
public final class Widgets {
	private Widgets() {}

	public static class GlassButton extends AbstractWidget {
		private final Runnable onPress;
		private final boolean primary;
		private final boolean danger;

		public GlassButton(int x, int y, int w, int h, String label, boolean primary, boolean danger, Runnable onPress) {
			super(x, y, w, h, Component.literal(label));
			this.onPress = onPress;
			this.primary = primary;
			this.danger = danger;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor ex, int mx, int my, float delta) {
			boolean hov = isHovered() && active;
			int r = Math.min(11, getHeight() / 2);
			if (primary) {
				// Bouton ambre liquide lumineux
				Glass.roundRect(ex, getX() + 1, getY() + 2, getWidth(), getHeight(), r, 0x40000000);
				Glass.roundRectGradient(ex, getX(), getY(), getWidth(), getHeight(), r,
						hov ? 0xFAFFE0A3 : 0xF0FAD089, hov ? 0xEDE39D49 : 0xE0D18432);
				ex.enableScissor(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1);
				ex.fillGradient(getX(), getY(), getRight(), getY() + getHeight() / 2,
						hov ? 0x66FFFFFF : 0x48FFFFFF, 0x00FFFFFF);
				ex.disableScissor();
				Glass.roundOutline(ex, getX(), getY(), getWidth(), getHeight(), r, 0xCCFFF4E0);
				ex.fill(getX() + r, getY() + 1, getRight() - r, getY() + 2, 0xAAFFFFFF);
				ex.centeredText(Minecraft.getInstance().font, getMessage(), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, Glass.DARKTXT);
			} else {
				// Bouton verre translucide avec biseau optique
				Glass.roundRect(ex, getX() + 1, getY() + 2, getWidth(), getHeight(), r, 0x26000000);
				Glass.roundRectGradient(ex, getX(), getY(), getWidth(), getHeight(), r,
						hov ? 0x996E492F : 0x6E4D311E, hov ? 0xA6452B19 : 0x802B180D);
				ex.enableScissor(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1);
				ex.fillGradient(getX(), getY(), getRight(), getY() + getHeight() / 2,
						hov ? 0x2EFFE8C8 : 0x1CFFE8C8, 0x00FFE8C8);
				ex.disableScissor();
				Glass.roundOutline(ex, getX(), getY(), getWidth(), getHeight(), r, hov ? 0xA6FFE6BD : 0x62D49B63);
				ex.fill(getX() + r, getY() + 1, getRight() - r, getY() + 2, hov ? 0x88FFF4E0 : 0x48FFE6BD);
				int col = danger ? Glass.RED : (active ? Glass.CREAM : Glass.FAINT);
				ex.centeredText(Minecraft.getInstance().font, getMessage(), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, col);
			}
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
			if (active && visible && e.button() == 0 && isMouseOver(e.x(), e.y())) {
				playButtonClickSound(Minecraft.getInstance().getSoundManager());
				onPress.run();
				return true;
			}
			return false;
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput out) {
			out.add(NarratedElementType.TITLE, getMessage());
		}
	}

	public static class GlassToggle extends AbstractWidget {
		private final BooleanSupplier getter;
		private final Consumer<Boolean> setter;

		public GlassToggle(int x, int y, int w, String label, BooleanSupplier g, Consumer<Boolean> s) {
			super(x, y, w, 20, Component.literal(label));
			this.getter = g;
			this.setter = s;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor ex, int mx, int my, float delta) {
			boolean on = getter.getAsBoolean();
			boolean hov = isHovered();
			if (hov) {
				Glass.roundRectGradient(ex, getX(), getY(), getWidth(), getHeight(), 7, 0x26FFE2B8, 0x12E89B48);
				Glass.roundOutline(ex, getX(), getY(), getWidth(), getHeight(), 7, 0x38FFE6BD);
			}
			ex.text(Minecraft.getInstance().font, getMessage(), getX() + 6, getY() + 6, on ? Glass.CREAM : Glass.MUTED, true);
			Glass.switchTrack(ex, getRight() - 36, getY() + 2, on, hov);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
			if (active && visible && e.button() == 0 && isMouseOver(e.x(), e.y())) {
				setter.accept(!getter.getAsBoolean());
				playButtonClickSound(Minecraft.getInstance().getSoundManager());
				AmberPathClient.CONFIG.save();
				return true;
			}
			return false;
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput out) {
			out.add(NarratedElementType.TITLE, getMessage());
		}
	}

	public static class GlassSlider extends AbstractWidget {
		private final double min, max, step;
		private final DoubleSupplier getter;
		private final Consumer<Double> setter;
		private final String suffix;

		public GlassSlider(int x, int y, int w, String label, double min, double max, double step, String suffix, DoubleSupplier g, Consumer<Double> s) {
			super(x, y, w, 26, Component.literal(label));
			this.min = min;
			this.max = max;
			this.step = step;
			this.suffix = suffix;
			this.getter = g;
			this.setter = s;
		}

		private void setFromMouse(double mouseX) {
			int trackX = getX() + 4, trackW = getWidth() - 8;
			double t = Math.max(0, Math.min(1, (mouseX - trackX) / trackW));
			double v = min + t * (max - min);
			v = Math.round(v / step) * step;
			setter.accept(Math.max(min, Math.min(max, v)));
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor ex, int mx, int my, float delta) {
			var font = Minecraft.getInstance().font;
			double v = getter.getAsDouble();
			String val = (step >= 1 ? String.valueOf((int) v) : String.format("%.2f", v)) + suffix;
			ex.text(font, getMessage(), getX() + 4, getY(), Glass.MUTED, true);
			ex.text(font, val, getRight() - 4 - font.width(val), getY(), Glass.AMBER, true);

			int ty = getY() + 16, tx = getX() + 4, tw = getWidth() - 8;
			// Rainure creusée dans le verre
			Glass.roundRect(ex, tx, ty, tw, 5, 3, 0x7A170B04);
			Glass.roundOutline(ex, tx, ty, tw, 5, 3, 0x45D49B63);

			double t = (v - min) / (max - min);
			int fw = Math.max(5, (int) (tw * t));
			Glass.roundRectGradient(ex, tx, ty, fw, 5, 3, 0xFFFAD089, 0xFFD18432);
			if (fw > 6) ex.fill(tx + 2, ty + 1, tx + fw - 2, ty + 2, 0x77FFFFFF);

			// Goutte de verre mobile
			int kx = tx + (int) ((tw - 12) * t);
			Glass.roundRect(ex, kx, ty - 3, 12, 12, 6, 0x40000000);
			Glass.roundRectGradient(ex, kx, ty - 4, 12, 12, 6,
					isHovered() ? 0xFFFFFAF2 : 0xFFF7E4C8, isHovered() ? 0xFFF0CFA0 : 0xFFD6AE7A);
			Glass.roundOutline(ex, kx, ty - 4, 12, 12, 6, 0xB3FFF4E0);
			Glass.roundRect(ex, kx + 3, ty - 3, 6, 4, 2, 0x88FFFFFF);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
			if (active && visible && e.button() == 0 && isMouseOver(e.x(), e.y())) {
				setFromMouse(e.x());
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
			if (active && visible && e.button() == 0) {
				setFromMouse(e.x());
				return true;
			}
			return false;
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput out) {
			out.add(NarratedElementType.TITLE, getMessage());
		}
	}

	public static class KeyBindButton extends AbstractWidget {
		private final Runnable onClick;
		private final java.util.function.Supplier<String> keyName;
		public boolean capturing;

		public KeyBindButton(int x, int y, int w, int h, java.util.function.Supplier<String> keyName, Runnable onClick) {
			super(x, y, w, h, Component.literal(""));
			this.keyName = keyName;
			this.onClick = onClick;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor ex, int mx, int my, float delta) {
			boolean hov = isHovered();
			Glass.roundRectGradient(ex, getX(), getY(), getWidth(), getHeight(), 8,
					capturing ? 0xA67D522B : (hov ? 0x996E492F : 0x6E4D311E), 0x802B180D);
			Glass.roundOutline(ex, getX(), getY(), getWidth(), getHeight(), 8,
					capturing ? 0xE6FFE8C2 : (hov ? 0x99D49B63 : 0x59C89058));
			ex.fill(getX() + 8, getY() + 1, getRight() - 8, getY() + 2, capturing ? 0x88FFFFFF : 0x44FFE6BD);
			String s = capturing ? "…" : keyName.get();
			ex.centeredText(Minecraft.getInstance().font, Component.literal(s), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, capturing ? Glass.AMBER : Glass.CREAM);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
			if (active && visible && e.button() == 0 && isMouseOver(e.x(), e.y())) {
				onClick.run();
				return true;
			}
			return false;
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput out) {
			out.add(NarratedElementType.TITLE, Component.literal(keyName.get()));
		}
	}

	public static class GlassField extends EditBox {
		public GlassField(int x, int y, int w, int h, String hint) {
			super(Minecraft.getInstance().font, x, y, w, h, Component.literal(""));
			setBordered(false);
			setTextColor(Glass.CREAM);
			setTextColorUneditable(Glass.MUTED);
			setHint(Component.literal(hint));
			setMaxLength(12);
			setCentered(true);
		}

		@Override
		public void extractWidgetRenderState(GuiGraphicsExtractor ex, int mx, int my, float delta) {
			boolean foc = isFocused();
			// Champ encastré dans le verre (plus sombre en haut, rebond lumineux en bas)
			Glass.roundRectGradient(ex, getX(), getY(), getWidth(), getHeight(), 9, 0x8C1B0D05, 0x73301A0E);
			Glass.roundOutline(ex, getX(), getY(), getWidth(), getHeight(), 9, foc ? 0xE6F5B96B : 0x5CC89058);
			// Rebonds de lumière sur la lèvre inférieure du champ encastré
			ex.fill(getX() + 9, getBottom() - 2, getRight() - 9, getBottom() - 1, foc ? 0x73FFE6BD : 0x2EFFE6BD);
			if (foc) {
				ex.fill(getX() + 9, getY() + 1, getRight() - 9, getY() + 2, 0x59FFE6BD);
			}
			super.extractWidgetRenderState(ex, mx, my, delta);
		}
	}
}