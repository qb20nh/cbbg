package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.sdl.SDLKeyboard;

@NullMarked
final class ReleaseGuiKeys {
  private ReleaseGuiKeys() {}

  static KeyEvent selectAll() {
    return new KeyEvent(
        InputConstants.KEY_A,
        SDLKeyboard.SDL_GetKeyFromScancode(InputConstants.KEY_A, (short) 0, false),
        InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER);
  }

  static KeyEvent event(int key) {
    return new KeyEvent(key, SDLKeyboard.SDL_GetKeyFromScancode(key, (short) 0, false), 0);
  }
}
