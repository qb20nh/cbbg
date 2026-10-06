package com.qb20nh.cbbg.gametest;

import net.minecraft.client.gui.components.AbstractWidget;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseGuiInput {
  private ReleaseGuiInput() {}

  static void click(AbstractWidget widget, double x, double y) {
    widget.onClick(x, y);
  }
}
