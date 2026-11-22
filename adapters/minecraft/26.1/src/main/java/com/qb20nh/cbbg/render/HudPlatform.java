package com.qb20nh.cbbg.render;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class HudPlatform {
  private HudPlatform() {}

  public static void register(Identifier id, Consumer<GuiGraphicsExtractor> draw) {
    HudElementRegistry.addLast(id, (graphics, deltaTracker) -> draw.accept(graphics));
  }

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
