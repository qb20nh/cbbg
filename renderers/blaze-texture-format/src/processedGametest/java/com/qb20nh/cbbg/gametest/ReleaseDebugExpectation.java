package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.textures.GpuTexture;
import org.jspecify.annotations.NullMarked;

/** 26.1 reports actual OpenGL internal formats and releases noise when disabled. */
@NullMarked
final class ReleaseDebugExpectation {
  private ReleaseDebugExpectation() {}

  static String main(GpuTexture texture, String mode) {
    return ReleaseAllocationFormat.actual(texture).toUpperCase(java.util.Locale.ROOT);
  }

  static int disabledFrames(int configuredDepth) {
    return 0;
  }
}
