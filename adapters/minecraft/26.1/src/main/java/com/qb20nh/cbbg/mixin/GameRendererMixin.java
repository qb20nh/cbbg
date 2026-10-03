package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import com.qb20nh.cbbg.render.MenuBlurScope;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public abstract class GameRendererMixin {
  @Shadow @Final private CrossFrameResourcePool resourcePool;
  @Unique private @Nullable PixelFormat cbbg$previousBlur;

  @WrapMethod(method = "processBlurEffect")
  private void cbbg$blur(Operation<Void> original) {
    PixelFormat desired =
        CbbgClient.isEnabled() ? CbbgConfig.get().pixelFormat() : PixelFormat.RGBA8;
    if (cbbg$previousBlur != desired) {
      resourcePool.clear();
      cbbg$previousBlur = desired;
    }
    MenuBlurScope.push(desired);
    try {
      original.call();
    } finally {
      MenuBlurScope.pop();
    }
  }
}
