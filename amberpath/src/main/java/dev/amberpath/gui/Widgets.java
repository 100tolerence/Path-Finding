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

/** Widgets verre personnalisés. */
public final class Widgets {
	private Widgets() {}

	public static class GlassButton extends AbstractWidget {
		private final Runnable onPress;
		private final boolean primary;
		private final boolean danger;
		public GlassButton(int x, int y, int w, int h, String label, boolean primary, boolean danger, Runnable onPress) {
			super(x, y, w, h, Component.literal(label));
			this.onPress = onPress; this.primary = primary; this.danger = danger;
		}
		@Override protected void extractWidgetRenderState(GuiGraphicsExtractor ex, int mx, int my, float delta) {
			boolean hov = isHovered() && active;
			int r = Math.min(10, getHeight() / 2);
			if (primary) {
				Glass.roundRectGradient(ex, getX(), getY(), getWidth(), getHeight(), r,
						hov ? 0xFFF7C882 : 0xFFF0B86E, hov ? 0xFFDD9C4E : 0xFFD08F42);
				Glass.roundOutline(ex, getX(), getY(), getWidth(), getHeight(), r, 0x99FFE0B0);
				ex.fill(getX() + r, getY() + 1, getRight() - r, getY() + 2, 0x66FFFFFF);
				ex.centeredText(Minecraft.getInstance().font, getMessage(), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, Glass.DARKTXT);
			} else {
				Glass.roundRectGradient(ex, getX(), getY(), getWidth(), getHeight(), r,
						hov ? 0xD95A3B24 : 0xB3402818, hov ? 0xD9402818 : 0xB32A1810);
				Glass.roundOutline(ex, getX(), getY(), getWidth(), getHeight(), r, hov ? 0x99C89058 : 0x59C89058);
				int col = danger ? Glass.RED : (active ? Glass.CREAM : Glass.FAINT);
				ex.centeredText(Minecraft.getInstance().font, getMessage(), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, col);
			}
		}
		@Override public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
			if (active && visible && e.button() == 0 && isMouseOver(e.x(), e.y())) {
				playButtonClickSound(Minecraft.getInstance().getSoundManager());
				onPress.run();
				return true;
			}
			return false;
		}
		@Override protected void updateWidgetNarration(NarrationElementOutput out) { out.add(NarratedElementType.TITLE, getMessage()); }
	}

	public static class GlassToggle extends AbstractWidget {
		private final BooleanSupplier getter; private final Consumer<Boolean> setter;
		public GlassToggle(int x, int y, int w, String label, BooleanSupplier g, Consumer<Boolean> s) {
			super(x, y, w, 20, Component.literal(label));
			this.getter = g; this.setter = s;
		}
		@Override protected void extractWidgetRenderState(GuiGraphicsExtractor ex, int mx, int my, float delta) {
			boolean on = getter.getAsBoolean();
			boolean hov = isHovered();
			if (hov) Glass.roundRect(ex, getX(), getY(), getWidth(), getHeight(), 6, 0x1AE8A85C);
			ex.text(Minecraft.getInstance().font, getMessage(), getX() + 6, getY() + 6, on ? Glass.CREAM : Glass.MUTED, true);
			Glass.switchTrack(ex, getRight() - 36, getY() + 3, on, hov);
		}
		@Override public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
			if (active && visible && e.button() == 0 && isMouseOver(e.x(), e.y())) {
				setter.accept(!getter.getAsBoolean());
				playButtonClickSound(Minecraft.getInstance().getSoundManager());
				AmberPathClient.CONFIG.save();
				return true;
			}
			return false;
		}
		@Override protected void updateWidgetNarration(NarrationElementOutput out) { out.add(NarratedElementType.TITLE, getMessage()); }
	}

	public static class GlassSlider extends AbstractWidget {
		private final double min, max, step; private final DoubleSupplier getter; private final Consumer<Double> setter;
		private final String suffix;
		public GlassSlider(int x, int y, int w, String label, double min, double max, double step, String suffix, DoubleSupplier g, Consumer<Double> s) {
			super(x, y, w, 26, Component.literal(label));
			this.min = min; this.max = max; this.step = step; this.suffix = suffix; this.getter = g; this.setter = s;
		}
		private void setFromMouse(double mouseX) {
			int trackX = getX() + 4, trackW = getWidth() - 8;
			double t = Math.max(0, Math.min(1, (mouseX - trackX) / trackW));
			double v = min + t * (max - min);
			v = Math.round(v / step) * step;
			setter.accept(Math.max(min, Math.min(max, v)));
		}
		@Override protected void extractWidgetRenderState(GuiGraphicsExtractor ex, int mx, int my, float delta) {
			var font = Minecraft.getInstance().font;
			double v = getter.getAsDouble();
			String val = (step >= 1 ? String.valueOf((int) v) : String.format("%.2f", v)) + suffix;
			ex.text(font, getMessage(), getX() + 4, getY(), Glass.MUTED, true);
			String vs = val;
			ex.text(font, vs, getRight() - 4 - font.width(vs), getY(), Glass.AMBER, true);
			int ty = getY() + 16, tx = getX() + 4, tw = getWidth() - 8;
			Glass.roundRect(ex, tx, ty, tw, 5, 3, 0xB31A0C05);
			double t = (v - min) / (max - min);
			int fw = Math.max(5, (int) (tw * t));
			Glass.roundRectGradient(ex, tx, ty, fw, 5, 3, 0xFFF0B86E, 0xFFC77F35);
			int kx = tx + (int) ((tw - 10) * t);
			Glass.roundRect(ex, kx, ty - 3, 10, 11, 5, isHovered() ? 0xFFF5E6D0 : 0xFFE8C79A);
			Glass.roundOutline(ex, kx, ty - 3, 10, 11, 5, 0x66C77F35);
		}
		@Override public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
			if (active && visible && e.button() == 0 && isMouseOver(e.x(), e.y())) { setFromMouse(e.x()); return true; }
			return false;
		}
		@Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
			if (active && visible && e.button() == 0) { setFromMouse(e.x()); return true; }
			return false;
		}
		@Override protected void updateWidgetNarration(NarrationElementOutput out) { out.add(NarratedElementType.TITLE, getMessage()); }
	}

	public static class KeyBindButton extends AbstractWidget {
		private final Runnable onClick; private final java.util.function.Supplier<String> keyName;
		public boolean capturing;
		public KeyBindButton(int x, int y, int w, int h, java.util.function.Supplier<String> keyName, Runnable onClick) {
			super(x, y, w, h, Component.literal(""));
			this.keyName = keyName; this.onClick = onClick;
		}
		@Override protected void extractWidgetRenderState(GuiGraphicsExtractor ex, int mx, int my, float delta) {
			boolean hov = isHovered();
			Glass.roundRectGradient(ex, getX(), getY(), getWidth(), getHeight(), 7,
					capturing ? 0xD96A4A2A : (hov ? 0xD95A3B24 : 0xB3402818), 0xB32A1810);
			Glass.roundOutline(ex, getX(), getY(), getWidth(), getHeight(), 7, capturing ? 0xCCFFD9A0 : (hov ? 0x99C89058 : 0x59C89058));
			String s = capturing ? "…" : keyName.get();
			ex.centeredText(Minecraft.getInstance().font, Component.literal(s), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, capturing ? Glass.AMBER : Glass.CREAM);
		}
		@Override public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
			if (active && visible && e.button() == 0 && isMouseOver(e.x(), e.y())) { onClick.run(); return true; }
			return false;
		}
		@Override protected void updateWidgetNarration(NarrationElementOutput out) { out.add(NarratedElementType.TITLE, Component.literal(keyName.get())); }
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
		@Override public void extractWidgetRenderState(GuiGraphicsExtractor ex, int mx, int my, float delta) {
			boolean foc = isFocused();
			Glass.roundRectGradient(ex, getX(), getY(), getWidth(), getHeight(), 8, 0xCC241208, 0xCC1A0C05);
			Glass.roundOutline(ex, getX(), getY(), getWidth(), getHeight(), 8, foc ? 0xCCE8A85C : 0x59C89058);
			if (foc) ex.fill(getX() + 8, getY() + 1, getRight() - 8, getY() + 2, 0x66FFD9A0);
			super.extractWidgetRenderState(ex, mx, my, delta);
		}
	}
}
