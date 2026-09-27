package com.qb20nh.cbbg.gametest.mixin;

import com.qb20nh.cbbg.gametest.ProcessedRenderObservations;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
@NullMarked
public class ProcessedFrameMixin {
  @Inject(method = "renderFrame", at = @At("RETURN"))
  private void cbbgTestFrame(boolean advanceGameTime, CallbackInfo ci) {
    ProcessedRenderObservations.afterFrame();
  }
}
