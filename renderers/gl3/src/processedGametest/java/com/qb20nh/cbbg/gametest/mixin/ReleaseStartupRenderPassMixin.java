package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.qb20nh.cbbg.gametest.ReleaseStartupObservations;
import com.qb20nh.cbbg.render.DitherPass;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DitherPass.class)
@NullMarked
public abstract class ReleaseStartupRenderPassMixin {
  @Inject(
      method =
          "render(IIIILcom/qb20nh/cbbg/api/DitherOptions;)Lcom/mojang/blaze3d/pipeline/TextureTarget;",
      at = @At("RETURN"))
  private void cbbgTestDraw(CallbackInfoReturnable<TextureTarget> ci) {
    ReleaseStartupObservations.draw();
  }
}
