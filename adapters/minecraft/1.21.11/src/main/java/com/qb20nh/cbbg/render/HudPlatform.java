package com.qb20nh.cbbg.render;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class HudPlatform {
  private HudPlatform() {}

  public static void register(Identifier id, Consumer<GuiGraphics> draw) {
    HudElementRegistry.addLast(id, (graphics, deltaTracker) -> draw.accept(graphics));
  }

  public static void text(
      GuiGraphics graphics, Font font, Component text, int x, int y, int color, boolean shadow) {
    graphics.drawString(font, text, x, y, color, shadow);
  }
}
