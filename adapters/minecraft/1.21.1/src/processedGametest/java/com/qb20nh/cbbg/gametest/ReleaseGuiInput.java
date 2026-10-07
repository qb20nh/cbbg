package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseGuiInput {
  private ReleaseGuiInput() {}

  static void click(double x, double y) {
    var screen = Objects.requireNonNull(ReleaseViewport.currentScreen(Minecraft.getInstance()));
    screen.mouseClicked(x, y, 0);
    screen.mouseReleased(x, y, 0);
  }

  static void drag(double x, double y, double endX) {
    var screen = Objects.requireNonNull(ReleaseViewport.currentScreen(Minecraft.getInstance()));
    screen.mouseClicked(x, y, 0);
    screen.mouseDragged(endX, y, 0, endX - x, 0);
    screen.mouseReleased(endX, y, 0);
  }

  static void key(int key) {
    Objects.requireNonNull(ReleaseViewport.currentScreen(Minecraft.getInstance()))
        .keyPressed(key, 0, 0);
  }

  static void selectAll() {
    key(InputConstants.KEY_A);
  }

  static void type(String text) {
    var screen = Objects.requireNonNull(ReleaseViewport.currentScreen(Minecraft.getInstance()));
    for (char character : text.toCharArray()) screen.charTyped(character, 0);
  }
}
