package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.GlStateManager;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Exercises readback while shaders are absent and while a noise load is still pending. */
@NullMarked
final class ReleaseFallbackScreenshots {
  private ReleaseFallbackScreenshots() {}

  static void check(ClientGameTestContext context) {
    context.runOnClient(
        client -> {
          Field shader =
              field(
                  "com.qb20nh.cbbg.render.CbbgShaders",
                  "net.minecraft.client.renderer.ShaderInstance dither");
          Field loading =
              field(
                  "com.qb20nh.cbbg.render.DitherController",
                  "java.util.concurrent.CompletableFuture loading");
          check(client, shader, null);
          check(client, loading, new CompletableFuture<>());
        });
  }

  private static void check(Minecraft client, Field field, @Nullable Object replacement) {
    var main = client.getMainRenderTarget();
    int active = GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
    int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    long presentations = ReleaseObservations.presentations();
    @Nullable Object original;
    try {
      original = field.get(null);
      field.set(null, replacement);
    } catch (IllegalAccessException failure) {
      throw new AssertionError("Cannot prepare screenshot fallback", failure);
    }
    try (@SuppressWarnings("PMD.UnusedLocalVariable")
        var restoration =
            new AutoCloseable() {
              @Override
              public void close() {
                GlStateManager._activeTexture(active);
                GlStateManager._bindTexture(texture);
                if (depth) GlStateManager._enableDepthTest();
                else GlStateManager._disableDepthTest();
                try {
                  field.set(null, original);
                } catch (IllegalAccessException failure) {
                  throw new AssertionError("Cannot restore screenshot fallback fixture", failure);
                }
              }
            }) {
      // A transparent float input must still yield an opaque screenshot in both fallback branches.
      GlStateManager._bindTexture(main.getColorTextureId());
      var pixel = BufferUtils.createFloatBuffer(4).put(new float[] {0.2f, 0.4f, 0.6f, 0});
      pixel.flip();
      GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
      GlStateManager._bindTexture(texture);
      GlStateManager._disableDepthTest();
      try (var image = Screenshot.takeScreenshot(main)) {
        int actual = ReleaseImagePixels.argb(image, 0, main.height - 1);
        if (actual >>> 24 != 255
            || Math.abs((actual >>> 16 & 255) - 51) > 1
            || Math.abs((actual >>> 8 & 255) - 102) > 1
            || Math.abs((actual & 255) - 153) > 1) {
          throw new AssertionError("Fallback screenshot lost float RGB, opacity or orientation");
        }
      }
      if (ReleaseObservations.presentations() != presentations
          || GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE) != active
          || GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != texture
          || GL11.glIsEnabled(GL11.GL_DEPTH_TEST)) {
        throw new AssertionError(
            "Fallback screenshot changed presentation, texture or depth state");
      }
    }
  }

  private static Field field(String owner, String signature) {
    try {
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      Field field = type.getDeclaredField(ReleaseMapping.memberName(owner, signature));
      field.setAccessible(true);
      return field;
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged GL3 screenshot fields changed", failure);
    }
  }
}
