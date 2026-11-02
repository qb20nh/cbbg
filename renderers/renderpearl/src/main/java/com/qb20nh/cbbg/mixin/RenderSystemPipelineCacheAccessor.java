package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@NullMarked
@Mixin(RenderSystem.class)
public interface RenderSystemPipelineCacheAccessor {
  @SuppressWarnings("DoNotCallSuggester") // Mixin supplies the field access at runtime.
  @Accessor("currentPipelineCache")
  static @Nullable PipelineCache cbbg$currentPipelineCache() {
    throw new AssertionError("Mixin accessor not applied");
  }
}
