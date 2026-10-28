package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.platform.TextureUtil;
import com.qb20nh.cbbg.gametest.ReleaseNoiseTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureUtil.class)
public abstract class ReleaseNoiseAllocationMixin {
  @Inject(method = "prepareImage(III)V", at = @At("HEAD"), cancellable = true)
  private static void cbbg$skipNoiseStorage(int texture, int width, int height, CallbackInfo ci) {
    if (ReleaseNoiseTextures.skipAllocation(width, height)) ci.cancel();
  }
}
