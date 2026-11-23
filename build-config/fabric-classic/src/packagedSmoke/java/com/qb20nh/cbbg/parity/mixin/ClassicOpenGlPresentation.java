package com.qb20nh.cbbg.parity.mixin;

import com.qb20nh.cbbg.parity.ClassicOpenGlPixels;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class ClassicOpenGlPresentation {
  @Inject(
      method = "runTick",
      at =
          @At(
              value = "INVOKE",
              target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
  private void cbbg$drawPixelFixture(boolean renderLevel, CallbackInfo ci) {
    ClassicOpenGlPixels.beforeBlit((Minecraft) (Object) this);
  }
}
