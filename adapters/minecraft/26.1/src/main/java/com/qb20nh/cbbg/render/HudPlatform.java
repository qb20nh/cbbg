package com.qb20nh.cbbg.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class HudPlatform {
  private HudPlatform() {}

  public static void text(
      GuiGraphicsExtractor graphics,
      Font font,
      Component text,
      int x,
      int y,
      int color,
      boolean shadow) {
    graphics.text(font, text, x, y, color, shadow);
  }
}
