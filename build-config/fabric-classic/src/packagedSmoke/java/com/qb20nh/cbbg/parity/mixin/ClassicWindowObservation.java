package com.qb20nh.cbbg.parity.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.parity.ClassicWindowCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
abstract class ClassicWindowObservation {
  @Inject(method = "flipFrame", at = @At("HEAD"))
  private static void cbbg$observeWindow(long window, CallbackInfo ci) {
    ClassicWindowCapture.beforeSwap();
  }
}
