package com.qb20nh.cbbg.config.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface ConfigCanvas {
  void fill(int x1, int y1, int x2, int y2, int color);

  void outline(int x, int y, int width, int height, int color);

  void centeredText(Font font, Component text, int x, int y, int color);

  void text(Font font, Component text, int x, int y, int color, boolean shadow);

  default void card(int x1, int y1, int x2, int y2) {
    fill(x1, y1, x2, y2, CbbgConfigWidgets.CARD_BG_COLOR);
    outline(x1, y1, x2 - x1, y2 - y1, CbbgConfigWidgets.CARD_BORDER_COLOR);
  }
}
