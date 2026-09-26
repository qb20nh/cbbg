package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.qb20nh.cbbg.render.FloatPipelines;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(RenderPass.class)
public abstract class RenderPassMixin {
  @Shadow @Final
  private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments;

  @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
  private RenderPipeline cbbg$matchFloatAttachments(RenderPipeline pipeline) {
    return MainTargetFormatSupport.isOpenGl()
        ? pipeline
        : FloatPipelines.forAttachments(pipeline, colorAttachments);
  }
}
