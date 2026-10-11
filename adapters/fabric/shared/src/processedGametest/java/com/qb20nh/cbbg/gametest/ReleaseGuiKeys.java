package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseGuiKeys {
  private ReleaseGuiKeys() {}

  static KeyEvent selectAll() {
    return new KeyEvent(InputConstants.KEY_A, 0, InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER);
  }

  static KeyEvent event(int key) {
    return new KeyEvent(key, 0, 0);
  }
}
