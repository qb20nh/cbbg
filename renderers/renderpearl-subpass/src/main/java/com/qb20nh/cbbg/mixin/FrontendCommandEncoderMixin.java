package com.qb20nh.cbbg.mixin;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.qb20nh.cbbg.render.FloatRenderPasses;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@NullMarked
@Mixin(FrontendCommandEncoder.class)
abstract class FrontendCommandEncoderMixin {
  @ModifyVariable(method = "createRenderPass", at = @At("HEAD"), argsOnly = true)
  // skipcq: JAVA-W0324
  private RenderPassDescriptor cbbg$matchAttachments(RenderPassDescriptor descriptor) {
    return FloatRenderPasses.forAttachments(descriptor);
  }
}
