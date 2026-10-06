package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class RenderScaleTargetFormat {
  private RenderScaleTargetFormat() {}

  static String name(RenderTarget target) {
    return Objects.requireNonNull(target.getColorTexture()).getFormat().name();
  }
}
