package com.qb20nh.cbbg.gametest;

import java.util.Objects;
import net.fabricmc.api.ClientModInitializer;
import org.jspecify.annotations.NullMarked;

/** Test-driver-only selection before the OpenGL device is created. */
@NullMarked
public final class DsaBenchmarkSettings implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    String mode = Objects.requireNonNull(System.getProperty("cbbg.test.dsa", "auto"));
    if (!"opengl".equals(System.getProperty("cbbg.test.backend"))
        || !(mode.equals("auto") || mode.equals("emulated"))) {
      throw new IllegalArgumentException("DSA benchmark requires OpenGL and a known selection");
    }
    try {
      var field =
          Class.forName("com.mojang.renderpearl.backend.opengl.GlDevice")
              .getDeclaredField("USE_GL_ARB_direct_state_access");
      field.setAccessible(true);
      field.setBoolean(null, mode.equals("auto"));
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Cannot select the benchmark DSA path", failure);
    }
  }
}
