package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import org.jspecify.annotations.NullMarked;

/** Keeps all live main-target allocations in sync with the active precision policy. */
@NullMarked
public final class MainTargets {
  private static final Set<MainTarget> TARGETS = Collections.newSetFromMap(new WeakHashMap<>());

  private MainTargets() {}

  public static void track(MainTarget target) {
    RenderSystem.assertOnRenderThread();
    TARGETS.add(target);
  }

  public static void refreshFormats() {
    RenderSystem.assertOnRenderThread();
    for (MainTarget target : TARGETS.toArray(MainTarget[]::new)) {
      var color = target.getColorTexture();
      if (color != null && !color.isClosed()) {
        target.resize(target.width, target.height);
      }
    }
  }
}
