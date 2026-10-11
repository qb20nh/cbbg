package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NullMarked;

/** Observes the applied 26.1 noise view and completion notification without linking the mod. */
@NullMarked
final class ReleaseGenerationStatus {
  private ReleaseGenerationStatus() {}

  static boolean mathActive() {
    return ReleaseGeneratingShutdownGameTest.mathActive();
  }

  static CompletableFuture<?> pending() {
    String owner = "com.qb20nh.cbbg.render.stbn.STBNGenerator";
    AtomicReference<?> pending =
        (AtomicReference<?>)
            Objects.requireNonNull(
                ReleaseShutdownGameTest.field(
                    owner, null, "java.util.concurrent.atomic.AtomicReference pendingFuture"));
    return (CompletableFuture<?>)
        Objects.requireNonNull(pending.get(), "Packaged generator has no pending future");
  }

  static boolean settled(int size) {
    try {
      String owner = "com.qb20nh.cbbg.render.DitherPresentation";
      Class<?> presentation = Class.forName(ReleaseMapping.className(owner));
      var viewField =
          presentation.getDeclaredField(
              ReleaseMapping.memberName(
                  owner, "com.mojang.blaze3d.textures.GpuTextureView noiseView"));
      viewField.setAccessible(true);
      GpuTextureView view = (GpuTextureView) viewField.get(null);
      String notifications = "com.qb20nh.cbbg.render.GenerationNotifications";
      Class<?> type = Class.forName(ReleaseMapping.className(notifications));
      var pending =
          type.getDeclaredField(
              ReleaseMapping.memberName(
                  notifications, "java.util.concurrent.CompletableFuture pending"));
      pending.setAccessible(true);
      return view != null
          && !view.isClosed()
          && view.getWidth(0) == size
          && view.getHeight(0) == size
          && pending.get(null) == null;
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged 26.1 generation observation changed", failure);
    }
  }
}
