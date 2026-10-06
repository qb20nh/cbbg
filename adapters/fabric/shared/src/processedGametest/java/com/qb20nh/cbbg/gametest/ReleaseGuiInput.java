package com.qb20nh.cbbg.gametest;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseGuiInput {
  private ReleaseGuiInput() {}

  static void click(AbstractWidget widget, double x, double y) {
    widget.onClick(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
  }
}
