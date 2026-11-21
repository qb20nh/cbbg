package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.jspecify.annotations.NullMarked;

/** Uses the same RGBA8 attachment declaration as the regular dither pass. */
@NullMarked
public final class BandingOutputFormat {
  private BandingOutputFormat() {}

  public static RenderPipeline.Builder rgba8(RenderPipeline.Builder builder) {
    return DitherOutputFormat.rgba8(builder);
  }
}
