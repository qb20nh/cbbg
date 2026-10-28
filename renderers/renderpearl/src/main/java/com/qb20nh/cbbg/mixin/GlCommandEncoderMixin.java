package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.opengl.GlDevice;
import com.qb20nh.cbbg.render.DitherPresentation;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
@NullMarked
public abstract class GlCommandEncoderMixin implements DitherPresentation.WindowEncoder {
  @Shadow @Final private GlDevice device;
  @Unique private boolean cbbg$windowPass;

  @WrapMethod(method = "presentTexture")
  private void cbbg$present(
      long window, GpuTextureView input, int width, int height, Operation<Void> original) {
    device.makeCurrent(window);
    if (!DitherPresentation.presentToWindow(input, width, height)) {
      original.call(window, DitherPresentation.present(input), width, height);
    }
  }

  @WrapMethod(method = "createRenderPass")
  private RenderPassBackend cbbg$createPass(
      RenderPassDescriptor descriptor, Operation<RenderPassBackend> original) {
    boolean previous = cbbg$windowPass;
    cbbg$windowPass =
        DitherPresentation.isWindowPass()
            && descriptor.colorAttachments().size() == 1
            && descriptor.label() != null
            && "CBBG dither".equals(descriptor.label().get());
    try {
      return original.call(descriptor);
    } finally {
      cbbg$windowPass = previous;
    }
  }

  @ModifyArg(
      method = "createRenderPass",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_glBindFramebuffer(II)V"),
      index = 1)
  private int cbbg$windowFramebuffer(int framebuffer) {
    return cbbg$windowPass ? 0 : framebuffer;
  }

  @ModifyArg(
      method = "createRenderPass",
      at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL33C;glDrawBuffers([I)V"),
      index = 0)
  private int[] cbbg$windowDrawBuffers(int[] buffers) {
    if (cbbg$windowPass) buffers[0] = DitherPresentation.windowDrawBuffer();
    return buffers;
  }
}
