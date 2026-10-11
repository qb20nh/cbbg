package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class ReleaseViewport {
  private ReleaseViewport() {}

  static boolean loadingOverlay(Minecraft client) {
    return client.gui.overlay() != null;
  }

  static void waitForChunks(TestSingleplayerContext world) {
    world.getConnection().waitForChunksRender();
  }

  static @Nullable Screen currentScreen(Minecraft client) {
    return client.gui.screen();
  }

  static void setScreen(Minecraft client, @Nullable Screen screen) {
    client.gui.setScreen(screen);
  }
}
