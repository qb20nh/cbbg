package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shader resources managed alongside Minecraft's shader reload and shutdown. */
@NullMarked
public final class CbbgShaders {
  private static final Logger LOGGER = LoggerFactory.getLogger("cbbg_lib");
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
      LOGGER.warn("Failed to load CBBG Lib shaders; dithering will be unavailable", failure);
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
