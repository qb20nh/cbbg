package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseBackend {
  private ReleaseBackend() {}

  static String[] identity() {
    var info = RenderSystem.getDevice().getDeviceInfo();
    return new String[] {info.backendName(), info.name(), info.driverInfo()};
  }
}
