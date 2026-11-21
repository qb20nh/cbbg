package com.qb20nh.cbbg.mixin;

import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.qb20nh.cbbg.render.FloatPipelines;
import com.qb20nh.cbbg.render.FloatPipelines.Source;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(FrontendRenderPipeline.class)
@NullMarked
public abstract class FrontendPipelineSourceMixin implements FloatPipelines.SourceHolder {
  @Unique private @Nullable Source cbbg$pipelineSource;

  @Override
  public @Nullable Source cbbg$pipelineSource() {
    return cbbg$pipelineSource;
  }

  @Override
  public void cbbg$pipelineSource(Source source) {
    cbbg$pipelineSource = source;
  }
}
