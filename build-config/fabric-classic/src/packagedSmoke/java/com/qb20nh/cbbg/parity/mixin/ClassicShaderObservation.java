package com.qb20nh.cbbg.parity.mixin;

import com.qb20nh.cbbg.parity.ClassicPackagedSmoke;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderInstance.class)
abstract class ClassicShaderObservation {
  @Inject(method = "apply", at = @At("RETURN"))
  private void cbbg$observeAppliedShader(CallbackInfo ci) {
    if (((ShaderInstance) (Object) this).getName().equals("cbbg:classic_dither")) {
      ClassicPackagedSmoke.observePresentation();
    }
  }
}
