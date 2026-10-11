package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;

@NullMarked
final class ReleaseGenerationStatus {
  private ReleaseGenerationStatus() {}

  static boolean mathActive() {
    for (var entry : Thread.getAllStackTraces().entrySet()) {
      if (!entry.getKey().getName().equals("cbbg-stbn") || !entry.getKey().isAlive()) continue;
      for (var frame : entry.getValue()) {
        if (frame.getClassName().equals("java.util.TimSort")) return true;
      }
    }
    return false;
  }

  static CompletableFuture<?> pending() {
    AtomicReference<?> pending =
        (AtomicReference<?>)
            Objects.requireNonNull(
                ReleasePackagedFields.get(
                    "com.qb20nh.cbbg.render.stbn.STBNGenerator",
                    "java.util.concurrent.atomic.AtomicReference pendingFuture"));
    return (CompletableFuture<?>)
        Objects.requireNonNull(pending.get(), "Packaged generator has no pending future");
  }

  static boolean settled(int size) {
    RenderSystem.assertOnRenderThread();
    int texture =
        (Integer)
            Objects.requireNonNull(
                ReleasePackagedFields.get(
                    "com.qb20nh.cbbg.render.DitherPresentation", "int texture"));
    if (texture <= 0 || !GL11.glIsTexture(texture) || !ReleaseNotificationUi.settled())
      return false;
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      GlStateManager._bindTexture(texture);
      return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) == size
          && GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) == size;
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }
}
