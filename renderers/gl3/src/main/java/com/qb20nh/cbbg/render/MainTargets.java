package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;

/** Includes RenderScale's main targets when synchronizing the precision policy. */
public final class MainTargets {
  private static final Set<MainTarget> TARGETS = Collections.newSetFromMap(new WeakHashMap<>());
  private static final Set<RenderTarget> POST_TARGETS =
      Collections.newSetFromMap(new WeakHashMap<>());

  private MainTargets() {}

  public static void track(MainTarget target) {
    RenderSystem.assertOnRenderThreadOrInit();
    TARGETS.add(target);
  }

  public static boolean contains(Object target) {
    return TARGETS.contains(target);
  }

  public static void trackPostTarget(RenderTarget target) {
    RenderSystem.assertOnRenderThreadOrInit();
    POST_TARGETS.add(target);
  }

  public static void refreshFormats() {
    RenderSystem.assertOnRenderThread();
    for (MainTarget target : TARGETS.toArray(MainTarget[]::new)) {
      if (target.getColorTextureId() > 0)
        target.resize(target.width, target.height, Minecraft.ON_OSX);
    }
    var previous = MenuBlurGuard.getActiveFormat();
    MenuBlurGuard.set(
        CbbgClient.isEnabled() ? CbbgConfig.get().pixelFormat() : CbbgConfig.PixelFormat.RGBA8);
    try {
      // Refresh before world rendering; reallocating at transparency-chain processing loses pixels.
      for (RenderTarget target : POST_TARGETS.toArray(RenderTarget[]::new)) {
        if (target.getColorTextureId() > 0)
          target.resize(target.width, target.height, Minecraft.ON_OSX);
      }
    } finally {
      MenuBlurGuard.set(previous);
    }
  }
}
