package com.qb20nh.cbbg.mixin;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.qb20nh.cbbg.render.FloatRenderPasses;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@NullMarked
@Mixin(FrontendRenderPass.class)
abstract class FrontendRenderPassMixin {
  @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
  private CompiledRenderPipeline cbbg$matchPipeline(CompiledRenderPipeline pipeline) {
    return FloatRenderPasses.forSubpass(pipeline, ((FrontendRenderPass) (Object) this).activeSubpass());
  }
}
