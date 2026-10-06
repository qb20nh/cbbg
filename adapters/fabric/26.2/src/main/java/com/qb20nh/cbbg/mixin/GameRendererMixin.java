package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import com.qb20nh.cbbg.render.MenuBlurGuard;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(GameRenderer.class)
@NullMarked
public abstract class GameRendererMixin {

  @Shadow @Final private CrossFrameResourcePool resourcePool;

  @WrapMethod(method = "processBlurEffect")
  private void cbbg$blur(Operation<Void> original) {
    boolean active = cbbg$beginPostProcessing();
    try {
      original.call();
    } finally {
      if (active) MenuBlurGuard.pop();
    }
  }

  @WrapMethod(method = "renderLevel")
  private void cbbg$world(DeltaTracker deltaTracker, Operation<Void> original) {
    boolean active = cbbg$beginPostProcessing();
    try {
      original.call(deltaTracker);
    } finally {
      if (active) MenuBlurGuard.pop();
    }
  }

  @Unique
  private boolean cbbg$beginPostProcessing() {
    final CbbgConfig.PixelFormat desired = cbbg$desiredPostFormat();

    if (MenuBlurGuard.updateLastBlurFormat(desired)) {
      // --- ImmediatelyFast compat: do not remove ---
      // World transparency and menu blur share cached vanilla post-processing targets.
      // Clearing prevents reuse across cbbg toggles and pixel-format changes.
      this.resourcePool.clear();
    }

    // Only enable the guard when cbbg is active and a float format is actually in use.
    if (!CbbgClient.isEnabled() || desired == CbbgConfig.PixelFormat.RGBA8) {
      return false;
    }

    MenuBlurGuard.push(desired);
    return true;
  }

  @Unique
  private static CbbgConfig.PixelFormat cbbg$desiredPostFormat() {
    if (!CbbgClient.isEnabled()) {
      return CbbgConfig.PixelFormat.RGBA8;
    }
    return MainTargetFormatSupport.getEffective(CbbgConfig.get().pixelFormat());
  }
}
