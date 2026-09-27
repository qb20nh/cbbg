package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendGpuSurface;
import com.qb20nh.cbbg.gametest.ProcessedRenderObservations;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrontendGpuSurface.class)
@NullMarked
public class ProcessedSurfaceMixin {
  @Inject(method = "blitFromTexture", at = @At("RETURN"))
  private void cbbgTestPresent(CommandEncoder encoder, GpuTextureView texture, CallbackInfo ci) {
    ProcessedRenderObservations.recordPresentation(texture);
  }
}
