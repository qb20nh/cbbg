package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class ReleaseViewport {
  private ReleaseViewport() {}

  static boolean loadingOverlay(Minecraft client) {
    return client.getOverlay() != null;
  }

  static void waitForChunks(TestSingleplayerContext world) {
    ReleaseWorldAccess.waitForChunksRender(world);
  }

  static RenderTarget mainTarget(Minecraft client) {
    return client.getMainRenderTarget();
  }

  static int framebufferWidth(Window window) {
    return window.getWidth();
  }

  static int framebufferHeight(Window window) {
    return window.getHeight();
  }

  static int windowWidth(Window window) {
    return window.getScreenWidth();
  }

  static int windowHeight(Window window) {
    return window.getScreenHeight();
  }

  static @Nullable Screen currentScreen(Minecraft client) {
    return client.screen;
  }

  static void setScreen(Minecraft client, @Nullable Screen screen) {
    client.setScreen(screen);
  }
}
