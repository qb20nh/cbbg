package com.qb20nh.cbbg.banding;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.io.IOException;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

@NullMarked
public final class BandingShader {
  private static @Nullable ResourceProvider resources;
  private static @Nullable String requested;
  private static @Nullable ShaderInstance shader;

  static {
    BandingDebug.closeShaderWith(BandingShader::close);
  }

  private BandingShader() {}

  public static void resources(@Nullable ResourceProvider provider) {
    resources = provider;
  }

  public static @Nullable ShaderInstance select(@Nullable ShaderInstance ordinary) {
    String name = BandingDebug.shader();
    if (name == null || resources == null) return ordinary;
    if (!name.equals(requested)) {
      close();
      requested = name;
      try {
        shader = new ShaderInstance(resources, name, DefaultVertexFormat.BLIT_SCREEN);
      } catch (IOException | RuntimeException failure) {
        LoggerFactory.getLogger("cbbg_banding_debug").warn("Could not load {}", name, failure);
      }
    }
    return shader == null ? ordinary : shader;
  }

  public static void close() {
    if (shader != null) shader.close();
    shader = null;
    requested = null;
  }
}
