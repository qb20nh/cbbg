package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseGuiInput {
  private ReleaseGuiInput() {}

  static void click(double x, double y) {
    var screen = Objects.requireNonNull(ReleaseViewport.currentScreen(Minecraft.getInstance()));
    var event =
        new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
    screen.mouseClicked(event, false);
    screen.mouseReleased(event);
  }

  static void drag(double x, double y, double endX) {
    var screen = Objects.requireNonNull(ReleaseViewport.currentScreen(Minecraft.getInstance()));
    screen.mouseClicked(
        new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)),
        false);
    var end =
        new MouseButtonEvent(endX, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
    screen.mouseDragged(end, endX - x, 0);
    screen.mouseReleased(end);
  }

  static void key(int key) {
    Objects.requireNonNull(ReleaseViewport.currentScreen(Minecraft.getInstance()))
        .keyPressed(ReleaseGuiKeys.event(key));
  }

  static void selectAll() {
    Objects.requireNonNull(ReleaseViewport.currentScreen(Minecraft.getInstance()))
        .keyPressed(ReleaseGuiKeys.selectAll());
  }

  static void type(String text) {
    var screen = Objects.requireNonNull(ReleaseViewport.currentScreen(Minecraft.getInstance()));
    text.codePoints().forEach(character -> ReleaseGuiCharacters.type(screen, character));
  }
}
