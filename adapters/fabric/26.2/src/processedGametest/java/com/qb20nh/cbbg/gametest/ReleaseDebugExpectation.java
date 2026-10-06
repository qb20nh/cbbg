package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.textures.GpuTexture;
import org.jspecify.annotations.NullMarked;

/** 26.2's debug entry exposes GL formats; Vulkan currently reports an unknown GL format. */
@NullMarked
final class ReleaseDebugExpectation {
  private ReleaseDebugExpectation() {}

  static String main(GpuTexture texture, String mode) {
    if (mode.equals("disabled") || !ReleaseBackend.identity()[0].equalsIgnoreCase("opengl")) {
      return "0xFFFFFFFF";
    }
    String actual = ReleaseAllocationFormat.actual(texture);
    return actual.equals("rgba32f") ? "0x8814" : actual.toUpperCase(java.util.Locale.ROOT);
  }

  static int disabledFrames(int configuredDepth) {
    return 0;
  }
}
