package com.qb20nh.cbbg.banding.mixin;

import com.qb20nh.cbbg.banding.BandingShader;
import com.qb20nh.cbbg.render.CbbgShaders;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NullMarked
@Mixin(value = CbbgShaders.class, remap = false)
abstract class BandingShaderLifecycleMixin {
  @Inject(method = "reload", at = @At("RETURN"))
  private static void cbbg$resources(ResourceProvider resources, CallbackInfo ci) {
    BandingShader.resources(resources);
  }

  @Inject(method = "close", at = @At("HEAD"))
  private static void cbbg$close(CallbackInfo ci) {
    BandingShader.close();
    BandingShader.resources(null);
  }
}
