package com.qb20nh.cbbg.compat.renderscale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.Cbbg;

/** Closes RenderScale's owned targets before RenderPearl destroys the device. */
public final class RenderScaleTargets {
  private RenderScaleTargets() {}

  public static void close() {
    if (!RenderScaleCompat.isLoaded()) {
      return;
    }
    RenderSystem.assertOnRenderThread();
    try {
      Class<?> type = Class.forName("dev.zelo.renderscale.RenderScale");
      Object renderer = type.getMethod("getInstance").invoke(null);
      if (renderer == null) {
        return;
      }
      Object borrowed = type.getField("clientRenderTarget").get(renderer);
      for (String name : new String[] {"renderTarget", "fsrIntermediateTarget"}) {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(renderer);
        if (value instanceof RenderTarget target && value != borrowed) {
          target.destroyBuffers();
          field.set(renderer, null);
        }
      }
    } catch (ReflectiveOperationException failure) {
      Cbbg.LOGGER.warn("Could not release RenderScale targets at shutdown", failure);
    }
  }
}
