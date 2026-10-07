package com.qb20nh.cbbg.config.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;

@NullMarked
record GuiGraphicsCanvas(GuiGraphics graphics) implements ConfigCanvas {
  @Override
  public void fill(int x1, int y1, int x2, int y2, int color) {
    graphics.fill(x1, y1, x2, y2, color);
  }

  @Override
  public void outline(int x, int y, int width, int height, int color) {
    graphics.renderOutline(x, y, width, height, color);
  }

  @Override
  public void centeredText(Font font, Component text, int x, int y, int color) {
    graphics.drawCenteredString(font, text, x, y, color);
  }

  @Override
  public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
    graphics.drawString(font, text, x, y, color, shadow);
  }
}
