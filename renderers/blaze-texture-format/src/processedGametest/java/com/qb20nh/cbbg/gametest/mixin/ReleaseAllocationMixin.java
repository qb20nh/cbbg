package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.qb20nh.cbbg.gametest.ReleaseObservations;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NullMarked
@Mixin(RenderTarget.class)
public abstract class ReleaseAllocationMixin {
  @Inject(method = "createBuffers", at = @At("RETURN"))
  private void cbbg$allocation(int width, int height, CallbackInfo ci) {
    ReleaseObservations.allocation((RenderTarget) (Object) this);
  }
}
