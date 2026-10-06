package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.qb20nh.cbbg.Cbbg;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jspecify.annotations.Nullable;

/** Shader resources managed alongside Minecraft's shader reload and shutdown. */
public final class CbbgShaders {
  private static @Nullable ShaderInstance dither;
  private static @Nullable ShaderInstance demo;

  private CbbgShaders() {}

  public static @Nullable ShaderInstance get(boolean demoMode) {
    return demoMode ? demo : dither;
  }

  public static void reload(ResourceProvider resources) {
    close();
    try {
      dither = new ShaderInstance(resources, "cbbg_dither", DefaultVertexFormat.BLIT_SCREEN);
      demo = new ShaderInstance(resources, "cbbg_demo", DefaultVertexFormat.BLIT_SCREEN);
    } catch (Exception failure) {
      close();
      Cbbg.LOGGER.warn("Failed to load CBBG shaders; dithering will be unavailable", failure);
    }
  }

  public static void close() {
    try {
      if (dither != null) dither.close();
    } finally {
      dither = null;
      try {
        if (demo != null) demo.close();
      } finally {
        demo = null;
      }
    }
  }
}
