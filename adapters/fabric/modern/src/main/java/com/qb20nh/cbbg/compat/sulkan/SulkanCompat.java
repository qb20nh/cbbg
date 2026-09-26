package com.qb20nh.cbbg.compat.sulkan;

import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.platform.LoaderPlatform;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class SulkanCompat {
  private static final boolean LOADED = LoaderPlatform.isModLoaded("sulkan");
  private static boolean loggedFailure;

  private SulkanCompat() {}

  public static boolean isShaderPackActive() {
    if (!LOADED) return false;
    try {
      // Sulkan checks both the saved setting and the active Vulkan backend.
      Object state =
          Class.forName("com.sulkan.shaders.runtime.ShaderRuntime")
              .getMethod("shadersEnabled")
              .invoke(null);
      if (state instanceof Boolean active) return active;
      throw new ReflectiveOperationException("Unexpected Sulkan shader state result");
    } catch (ReflectiveOperationException | LinkageError | ClassCastException failure) {
      if (!loggedFailure) {
        loggedFailure = true;
        Cbbg.LOGGER.warn("Could not read Sulkan shader state; suspending cbbg.", failure);
      }
      return true;
    }
  }
}
