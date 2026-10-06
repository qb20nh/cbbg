package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.qb20nh.cbbg.gametest.ReleaseObservations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTarget.class)
public abstract class ReleasePresentMixin {
  @Inject(method = "blitToScreen(IIZ)V", at = @At("HEAD"))
  private void cbbg$present(int width, int height, boolean disableBlend, CallbackInfo info) {
    ReleaseObservations.present((RenderTarget) (Object) this);
  }
}
