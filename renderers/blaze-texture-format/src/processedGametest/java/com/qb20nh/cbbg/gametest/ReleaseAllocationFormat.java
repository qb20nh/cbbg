package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.textures.GpuTexture;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** 26.1 records the actual OpenGL format override, since TextureFormat remains RGBA8. */
@NullMarked
final class ReleaseAllocationFormat {
  private ReleaseAllocationFormat() {}

  static String attempt(Object ignored) {
    try {
      String owner = "com.qb20nh.cbbg.render.FloatAttachments";
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      Object value =
          type.getMethod(
                  ReleaseMapping.memberName(owner, "java.lang.Integer forcedInternalFormat()"))
              .invoke(null);
      return name(value == null ? GL11.GL_RGBA8 : (Integer) value);
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException failure) {
      throw new LinkageError("Cannot inspect packaged float allocation override", failure);
    } catch (InvocationTargetException failure) {
      throw new AssertionError("Packaged float allocation override failed", failure.getCause());
    }
  }

  static String actual(GpuTexture texture) {
    return name(ReleaseLifecycleGameTest.format(texture));
  }

  static List<String> exhaustedAttempts() {
    return List.of("rgba32f", "rgba16f", "rgba8", "rgba32f", "rgba16f", "rgba8");
  }

  static List<String> firstFallbackAttempts() {
    return List.of("rgba32f", "rgba16f");
  }

  static List<String> secondFallbackAttempts() {
    return List.of("rgba32f", "rgba16f", "rgba8");
  }

  static String recoveryFormat() {
    return "rgba32f";
  }

  private static String name(int format) {
    return switch (format) {
      case GL30.GL_RGBA32F -> "rgba32f";
      case GL30.GL_RGBA16F -> "rgba16f";
      case GL11.GL_RGBA8 -> "rgba8";
      default -> throw new AssertionError("Unexpected OpenGL color format: " + format);
    };
  }
}
