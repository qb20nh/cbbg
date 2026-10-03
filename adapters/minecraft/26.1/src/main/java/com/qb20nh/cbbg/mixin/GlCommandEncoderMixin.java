package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.render.DitherPresentation;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderMixin {
  @WrapMethod(method = "presentTexture")
  private void cbbg$present(GpuTextureView input, Operation<Void> original) {
    original.call(DitherPresentation.present(input));
  }
}
