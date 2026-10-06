package com.qb20nh.cbbg.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.qb20nh.cbbg.gametest.ReleaseAllocationGameTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Checks old GL identities at destruction before resize can reuse their numeric names. */
@Mixin(value = RenderTarget.class, priority = 2000)
public abstract class ReleaseAllocationDisposalMixin {
  @Inject(method = "createBuffers", at = @At("HEAD"))
  private void cbbg$begin(int width, int height, boolean clearError, CallbackInfo info) {
    ReleaseAllocationGameTest.allocating((RenderTarget) (Object) this, false);
  }

  @Inject(
      method = "createBuffers",
      at =
          @At(
              value = "INVOKE",
              target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;unbindRead()V",
              ordinal = 0,
              shift = At.Shift.AFTER))
  private void cbbg$ready(int width, int height, boolean clearError, CallbackInfo info) {
    ReleaseAllocationGameTest.allocating((RenderTarget) (Object) this, true);
  }

  @WrapMethod(method = "destroyBuffers")
  private void cbbg$disposed(Operation<Void> original) {
    var target = (RenderTarget) (Object) this;
    int color = target.getColorTextureId();
    int depth = target.getDepthTextureId();
    int framebuffer = target.frameBufferId;
    original.call();
    ReleaseAllocationGameTest.disposed(target, color, depth, framebuffer);
  }
}
