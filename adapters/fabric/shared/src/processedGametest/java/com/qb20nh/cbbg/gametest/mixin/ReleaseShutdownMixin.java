package com.qb20nh.cbbg.gametest.mixin;

import com.qb20nh.cbbg.gametest.ReleaseShutdownGameTest;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
@NullMarked
public abstract class ReleaseShutdownMixin {
  @Inject(
      method = "close",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/renderer/GameRenderer;close()V",
              shift = At.Shift.BEFORE))
  private void cbbgTestClose(CallbackInfo info) {
    ReleaseShutdownGameTest.beforeRendererClose();
  }
}
