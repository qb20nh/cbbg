package com.qb20nh.cbbg.config.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class ClientScreenAccess {
  private ClientScreenAccess() {}

  static void setScreen(Minecraft minecraft, @Nullable Screen screen) {
    minecraft.gui.setScreen(screen);
  }
}
