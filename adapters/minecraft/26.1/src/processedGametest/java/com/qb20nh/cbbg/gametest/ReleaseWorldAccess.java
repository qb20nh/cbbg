package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseWorldAccess {
  private ReleaseWorldAccess() {}

  static void waitForChunksRender(TestSingleplayerContext world) {
    world.getClientLevel().waitForChunksRender();
  }

  static boolean imageClosed(NativeImage image) {
    return image.isClosed();
  }
}
