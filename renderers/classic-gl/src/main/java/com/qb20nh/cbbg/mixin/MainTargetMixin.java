package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.qb20nh.cbbg.platform.LoaderPlatform;
import com.qb20nh.cbbg.render.ClassicFramebuffers;
import java.nio.IntBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MainTarget.class)
public abstract class MainTargetMixin {
  @Inject(method = "<init>", at = @At("RETURN"))
  private void cbbg$track(int width, int height, CallbackInfo ci) {
    MainTarget target = (MainTarget) (Object) this;
    ClassicFramebuffers.track(target);
    if (LoaderPlatform.isModLoaded("satin")) {
      // MainTarget's constructor bypasses createBuffers, where Satin initializes readable depth.
      // Use the normal lifecycle so fresh targets (including RenderScale's) are immediately usable.
      target.resize(target.width, target.height, false);
    }
  }

  @WrapOperation(
      method = "allocateColorAttachment",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/blaze3d/platform/GlStateManager;_texImage2D(IIIIIIIILjava/nio/IntBuffer;)V"))
  private void cbbg$color(
      int target,
      int level,
      int internalFormat,
      int width,
      int height,
      int border,
      int format,
      int type,
      IntBuffer pixels,
      Operation<Void> original) {
    ClassicFramebuffers.allocateColor(
        internalFormat,
        selected ->
            original.call(target, level, selected, width, height, border, format, type, pixels));
  }
}
