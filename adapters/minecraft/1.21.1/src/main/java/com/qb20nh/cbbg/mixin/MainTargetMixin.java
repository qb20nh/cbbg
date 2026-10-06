package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import com.qb20nh.cbbg.render.MainTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MainTarget.class)
public abstract class MainTargetMixin {
  @Inject(method = "<init>", at = @At("RETURN"))
  private void cbbg$format(int width, int height, CallbackInfo info) {
    var target = (MainTarget) (Object) this;
    if (CbbgClient.isEnabled()) {
      try {
        MainTargetFormatSupport.allocate(target, CbbgConfig.get().pixelFormat(), true);
      } catch (RuntimeException failure) {
        target.destroyBuffers();
        throw failure;
      }
    }
    MainTargets.track(target);
  }
}
