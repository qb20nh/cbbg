package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NullMarked;

/** Observes the applied 26.2 noise view and generation notification without linking the mod. */
@NullMarked
final class ReleaseGenerationStatus {
  private ReleaseGenerationStatus() {}

  static CompletableFuture<?> pending() {
    try {
      String owner = "com.qb20nh.cbbg.render.stbn.STBNGenerator";
      Object result =
          Class.forName(ReleaseMapping.className(owner))
              .getMethod(
                  ReleaseMapping.memberName(owner, "java.util.concurrent.CompletableFuture get()"))
              .invoke(null);
      return (CompletableFuture<?>)
          Objects.requireNonNull(result, "Packaged generator has no pending future");
    } catch (ReflectiveOperationException error) {
      throw new LinkageError("Cannot inspect pending noise generation", error);
    }
  }

  static boolean settled(int size) {
    try {
      String owner = "com.qb20nh.cbbg.render.CbbgDither";
      Class<?> controller = Class.forName(ReleaseMapping.className(owner));
      boolean busy =
          (boolean)
              Objects.requireNonNull(
                  controller
                      .getMethod(ReleaseMapping.memberName(owner, "boolean isGenerating()"))
                      .invoke(null));
      var managerField =
          controller.getDeclaredField(
              ReleaseMapping.memberName(
                  owner, "com.qb20nh.cbbg.render.stbn.StbnTextureManager stbnManager"));
      managerField.setAccessible(true);
      Object manager = Objects.requireNonNull(managerField.get(null));
      GpuTextureView view =
          (GpuTextureView)
              manager
                  .getClass()
                  .getMethod(
                      ReleaseMapping.memberName(
                          "com.qb20nh.cbbg.render.stbn.StbnTextureManager",
                          "com.mojang.blaze3d.textures.GpuTextureView getView()"))
                  .invoke(manager);
      return !busy
          && view != null
          && !view.isClosed()
          && view.getWidth(0) == size
          && view.getHeight(0) == size;
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged 26.2 generation observation changed", failure);
    }
  }
}
