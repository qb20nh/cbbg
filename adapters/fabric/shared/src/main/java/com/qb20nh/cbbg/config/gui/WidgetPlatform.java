package com.qb20nh.cbbg.config.gui;

import java.util.function.Function;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;

final class WidgetPlatform {
  private WidgetPlatform() {}

  static void tooltip(AbstractWidget widget, Component text) {
    widget.setTooltip(Tooltip.create(text));
  }

  static <T> CycleButton.Builder<T> cycleTooltip(
      CycleButton.Builder<T> builder, Function<T, Component> text) {
    return builder.withTooltip(value -> Tooltip.create(text.apply(value)));
  }

  static Button button(
      Component label,
      Button.OnPress action,
      int x,
      int y,
      int width,
      int height,
      Component tooltip) {
    Button.Builder builder = Button.builder(label, action).bounds(x, y, width, height);
    if (tooltip != null) builder.tooltip(Tooltip.create(tooltip));
    return builder.build();
  }

  @SafeVarargs
  static <T> CycleButton.Builder<T> cycle(Function<T, Component> label, T initial, T... values) {
    return CycleButton.builder(label, initial).withValues(values);
  }

  static String filterText(String text) {
    return StringUtil.filterText(text);
  }
}
