package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** 26.2 exposes native Vulkan formats directly and OpenGL formats through its override. */
@NullMarked
final class ReleaseAllocationFormat {
  private ReleaseAllocationFormat() {}

  static String attempt(Object format) {
    GpuFormat requested = (GpuFormat) format;
    if (requested != GpuFormat.RGBA8_UNORM
        || !RenderSystem.getDevice().getDeviceInfo().backendName().equalsIgnoreCase("opengl")) {
      return gpuName(requested);
    }
    try {
      String owner = "com.qb20nh.cbbg.config.CbbgConfig";
      Class<?> configType = Class.forName(ReleaseMapping.className(owner));
      Object config =
          configType.getMethod(ReleaseMapping.memberName(owner, owner + " get()")).invoke(null);
      Object configured =
          configType
              .getMethod(ReleaseMapping.memberName(owner, owner + "$PixelFormat pixelFormat()"))
              .invoke(config);
      String supportOwner = "com.qb20nh.cbbg.render.MainTargetFormatSupport";
      Class<?> supportType = Class.forName(ReleaseMapping.className(supportOwner));
      Class<?> formatType = Class.forName(ReleaseMapping.className(owner + "$PixelFormat"));
      Object effective =
          supportType
              .getMethod(
                  ReleaseMapping.memberName(
                      supportOwner, owner + "$PixelFormat getEffective(" + owner + "$PixelFormat)"),
                  formatType)
              .invoke(null, configured);
      return (String)
          java.util.Objects.requireNonNull(
              formatType
                  .getMethod(
                      ReleaseMapping.memberName(
                          owner + "$PixelFormat", "java.lang.String getSerializedName()"))
                  .invoke(effective));
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException failure) {
      throw new LinkageError("Cannot inspect packaged main-target format", failure);
    } catch (InvocationTargetException failure) {
      throw new AssertionError("Packaged main-target format lookup failed", failure.getCause());
    }
  }

  static String actual(GpuTexture texture) {
    if (texture instanceof GlTexture gl) {
      int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      try {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, gl.glId());
        return glName(
            GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT));
      } finally {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
      }
    }
    return gpuName(texture.getFormat());
  }

  static List<String> exhaustedAttempts() {
    // MainTarget retries the requested 3x3 allocation at its default 854x480 size.
    return List.of("rgba8", "rgba8");
  }

  static List<String> firstFallbackAttempts() {
    return List.of("rgba32f", "rgba16f");
  }

  static List<String> secondFallbackAttempts() {
    return List.of("rgba16f", "rgba8");
  }

  static String recoveryFormat() {
    return "rgba8";
  }

  private static String gpuName(GpuFormat format) {
    if (format == GpuFormat.RGBA32_FLOAT) return "rgba32f";
    if (format == GpuFormat.RGBA16_FLOAT) return "rgba16f";
    if (format == GpuFormat.RGBA8_UNORM) return "rgba8";
    throw new AssertionError("Unexpected GPU color format: " + format);
  }

  private static String glName(int format) {
    return switch (format) {
      case GL30.GL_RGBA32F -> "rgba32f";
      case GL30.GL_RGBA16F -> "rgba16f";
      case GL11.GL_RGBA8 -> "rgba8";
      default -> throw new AssertionError("Unexpected OpenGL color format: " + format);
    };
  }
}
