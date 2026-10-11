package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.qb20nh.cbbg.gametest.ReleaseAllocationGameTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Selects the constructed target before CBBG upgrades its vanilla attachments. */
@Mixin(value = MainTarget.class, priority = 2000)
public abstract class ReleaseMainAllocationMixin {
  @Inject(
      method = "<init>",
      at =
          @At(
              value = "INVOKE",
              target = "Lcom/mojang/blaze3d/pipeline/MainTarget;createFrameBuffer(II)V",
              shift = At.Shift.AFTER))
  private void cbbg$ready(int width, int height, CallbackInfo info) {
    ReleaseAllocationGameTest.allocating((MainTarget) (Object) this, true);
  }
}
