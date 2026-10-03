package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.gametest.ReleaseObservations;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NullMarked
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class ReleasePresentMixin {
  @Inject(method = "presentTexture", at = @At("HEAD"))
  private void cbbg$present(GpuTextureView texture, CallbackInfo info) {
    ReleaseObservations.present(texture);
  }
}
