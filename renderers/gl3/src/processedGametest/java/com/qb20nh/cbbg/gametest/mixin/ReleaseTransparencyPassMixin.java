package com.qb20nh.cbbg.gametest.mixin;

import com.qb20nh.cbbg.gametest.ReleaseTransparencyObservations;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PostChain.class)
public abstract class ReleaseTransparencyPassMixin {
  @Inject(method = "process(F)V", at = @At("RETURN"))
  private void cbbg$processed(float partialTick, CallbackInfo callback) {
    ReleaseTransparencyObservations.processed((PostChain) (Object) this);
  }
}
