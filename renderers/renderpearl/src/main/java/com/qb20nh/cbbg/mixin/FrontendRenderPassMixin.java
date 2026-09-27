package com.qb20nh.cbbg.mixin;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.render.FloatPipelines;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(FrontendRenderPass.class)
@NullMarked
public abstract class FrontendRenderPassMixin {
  @Shadow @Final
  private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments;

  @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
  private CompiledRenderPipeline cbbg$matchFloatAttachments(CompiledRenderPipeline pipeline) {
    return CbbgClient.areShadersActive()
        ? pipeline
        : FloatPipelines.forAttachments(pipeline, colorAttachments);
  }
}
