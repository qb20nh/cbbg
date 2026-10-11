package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.TestSingleplayerContext;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseWorldAccess {
  private ReleaseWorldAccess() {}

  static void waitForChunksRender(TestSingleplayerContext world) {
    world.getClientWorld().waitForChunksRender();
  }
}
