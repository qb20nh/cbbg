package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.render.DitherPresentation;
import com.qb20nh.cbbg.render.WindowRenderPass;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderMixin {
  @Shadow @Final private GlDevice device;
  @Shadow private boolean inRenderPass;
  @Shadow private @Nullable RenderPipeline lastPipeline;

  @WrapMethod(method = "presentTexture")
  private void cbbg$present(GpuTextureView input, Operation<Void> original) {
    if (DitherPresentation.presentToWindow(
        input,
        () -> {
          inRenderPass = true;
          device.debugLabels().pushDebugGroup(() -> "CBBG window dither");
          GlStateManager._glBindFramebuffer(36160, 0);
          GlStateManager._disableScissorTest();
          GlStateManager._viewport(0, 0, input.getWidth(0), input.getHeight(0));
          lastPipeline = null;
          return WindowRenderPass.create((GlCommandEncoder) (Object) this, device);
        })) {
      WindowRenderPass.finish();
      return;
    }
    original.call(DitherPresentation.present(input));
  }
}
