package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.NullMarked;

/** Minecraft 26.1's GPU identity API. */
@NullMarked
final class ReleaseBackend {
  private ReleaseBackend() {}

  static String[] identity() {
    var device = RenderSystem.getDevice();
    return new String[] {device.getBackendName(), device.getRenderer(), device.getVersion()};
  }
}
