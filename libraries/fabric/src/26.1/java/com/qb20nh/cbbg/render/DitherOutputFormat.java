package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class DitherOutputFormat {
  private DitherOutputFormat() {}

  static RenderPipeline.Builder rgba8(RenderPipeline.Builder builder) {
    return builder
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withDepthStencilState(Optional.empty());
  }
}
