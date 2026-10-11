package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import com.qb20nh.cbbg.render.MainTargets;
import com.qb20nh.cbbg.render.MenuBlurGuard;
import java.util.Map;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(PostChain.class)
public abstract class PostChainMixin {
  @Shadow @Final private String name;
  @Shadow @Final private Map<String, RenderTarget> customRenderTargets;

  @Unique
  private boolean cbbg$managed() {
    return name.equals("minecraft:shaders/post/blur.json")
        || name.equals("minecraft:shaders/post/transparency.json");
  }

  @Unique
  private CbbgConfig.PixelFormat cbbg$desired() {
    return CbbgClient.isEnabled()
        ? MainTargetFormatSupport.getEffective(CbbgConfig.get().pixelFormat())
        : CbbgConfig.PixelFormat.RGBA8;
  }

  @WrapMethod(method = "addTempTarget")
  private void cbbg$allocate(String targetName, int width, int height, Operation<Void> original) {
    var previous = MenuBlurGuard.getActiveFormat();
    if (cbbg$managed()) MenuBlurGuard.set(cbbg$desired());
    try {
      original.call(targetName, width, height);
      RenderTarget target = customRenderTargets.get(targetName);
      if (cbbg$managed() && target != null) MainTargets.trackPostTarget(target);
    } finally {
      MenuBlurGuard.set(previous);
    }
  }

  @WrapMethod(method = "resize")
  private void cbbg$resize(int width, int height, Operation<Void> original) {
    var previous = MenuBlurGuard.getActiveFormat();
    if (cbbg$managed()) MenuBlurGuard.set(cbbg$desired());
    try {
      original.call(width, height);
    } finally {
      MenuBlurGuard.set(previous);
    }
  }
}
