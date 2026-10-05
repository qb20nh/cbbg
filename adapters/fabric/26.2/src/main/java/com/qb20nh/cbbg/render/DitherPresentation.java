package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;

/** Minecraft 26.2 GPU operations for the shared noise lifecycle. */
@NullMarked
final class DitherPresentation {
  private DitherPresentation() {}

  static boolean hasOverlay() {
    return Minecraft.getInstance().gui.overlay() != null;
  }

  static void allocate(int size) {
    CbbgDither.allocateNoise(size);
  }

  static boolean isReady() {
    return CbbgDither.isNoiseReady();
  }

  static void upload(NativeImage frame) {
    CbbgDither.uploadNoise(frame);
  }

  static boolean hasDetectedNoFloatFormats() {
    return MainTargetFormatSupport.hasDetectedNoFloatFormats();
  }

  static void close() {
    CbbgDither.closeGpu();
  }
}
