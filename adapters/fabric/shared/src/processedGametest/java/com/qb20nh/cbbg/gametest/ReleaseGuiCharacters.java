package com.qb20nh.cbbg.gametest;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseGuiCharacters {
  private ReleaseGuiCharacters() {}

  static void type(Screen screen, int character) {
    screen.charTyped(new CharacterEvent(character));
  }
}
