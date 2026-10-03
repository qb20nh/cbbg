package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.NullMarked;

/** Minecraft 26.2's GPU identity API. */
@NullMarked
final class ReleaseBackend {
  private ReleaseBackend() {}

  static String[] identity() {
    var info = RenderSystem.getDevice().getDeviceInfo();
    return new String[] {info.backendName(), info.name(), info.driverInfo()};
  }
}
