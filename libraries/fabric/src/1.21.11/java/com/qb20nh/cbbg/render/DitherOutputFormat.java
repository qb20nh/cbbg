package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class DitherOutputFormat {
  private DitherOutputFormat() {}

  static RenderPipeline.Builder rgba8(RenderPipeline.Builder builder) {
    return builder.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false);
  }
}
