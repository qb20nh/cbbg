package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class ReleaseObservations {
  private static long presentations;
  private static @Nullable GpuTextureView output;
  private static @Nullable GpuTextureView noise;
  private static long blurAllocations;
  private static int blurFormat;
  private static boolean observingBlur;

  private ReleaseObservations() {}

  // Presentation must use CBBG's texture; GPU debug labels are optional.
  @SuppressWarnings("ReferenceEquality")
  public static void present(GpuTextureView texture) {
    var live = ReleaseShutdownResources.live();
    if (live != null && texture.texture() == live[1].texture()) {
      output = texture;
      presentations++;
    }
  }

  static long presentations() {
    return presentations;
  }

  static @Nullable GpuTextureView output() {
    return output;
  }

  public static void bind(String name, GpuTextureView texture) {
    if ("NoiseSampler".equals(name)) noise = texture;
  }

  static GpuTextureView noise() {
    return java.util.Objects.requireNonNull(noise);
  }

  public static void allocation(com.mojang.blaze3d.pipeline.RenderTarget target) {
    var texture = target.getColorTexture();
    if (observingBlur && texture != null) {
      blurAllocations++;
      blurFormat = ReleaseLifecycleGameTest.format(texture);
    }
  }

  static void observingBlur(boolean active) {
    observingBlur = active;
  }

  static long blurAllocations() {
    return blurAllocations;
  }

  static int blurFormat() {
    return blurFormat;
  }
}
