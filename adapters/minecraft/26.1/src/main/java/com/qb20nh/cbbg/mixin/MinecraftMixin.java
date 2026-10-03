package com.qb20nh.cbbg.mixin;

import com.qb20nh.cbbg.render.DitherController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
  @Inject(method = "runTick", at = @At("HEAD"))
  private void cbbg$beginFrame(boolean renderLevel, CallbackInfo info) {
    DitherController.beginFrame();
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void cbbg$close(CallbackInfo info) {
    DitherController.close();
  }
}
