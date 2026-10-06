package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class ReleaseObservations {
  private static long presentations;
  private static @Nullable TextureTarget output;
  private static int noise;

  private ReleaseObservations() {}

  public static void drawn(TextureTarget target, int noiseTexture) {
    output = target;
    noise = noiseTexture;
  }

  @SuppressWarnings("ReferenceEquality")
  public static void present(RenderTarget target) {
    if (target == output) presentations++;
  }

  static long presentations() {
    return presentations;
  }

  static @Nullable TextureTarget output() {
    return output;
  }

  static int noise() {
    return noise;
  }
}
