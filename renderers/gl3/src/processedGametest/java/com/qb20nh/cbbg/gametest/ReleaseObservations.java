package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class ReleaseObservations {
  private static @Nullable TextureTarget output;

  private ReleaseObservations() {}

  public static void drawn(TextureTarget target) {
    output = target;
  }

  static long presentations() {
    return (Long)
        Objects.requireNonNull(
            ReleaseShutdownGameTest.field(
                "com.qb20nh.cbbg.render.DitherController", null, "long presentations"));
  }

  static @Nullable TextureTarget output() {
    return output;
  }

  static int noise() {
    return (Integer)
        Objects.requireNonNull(
            ReleaseShutdownGameTest.field(
                "com.qb20nh.cbbg.render.DitherPresentation", null, "int texture"));
  }
}
