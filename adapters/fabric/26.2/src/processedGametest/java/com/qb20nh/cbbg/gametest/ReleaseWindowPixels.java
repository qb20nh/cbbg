package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/** Compares the native window pass with the offscreen pass at each noise frame. */
@NullMarked
final class ReleaseWindowPixels {
  private ReleaseWindowPixels() {}

  static void check(ClientGameTestContext context, TextureTarget source, int frames, boolean demo) {
    if (!context.computeOnClient(
        client ->
            RenderSystem.getDevice().getDeviceInfo().backendName().equalsIgnoreCase("OpenGL"))) {
      return;
    }
    context.runOnClient(
        client -> {
          var encoder = RenderSystem.getDevice().createCommandEncoder();
          encoder.submit();
          Objects.requireNonNull(ReleasePixelGameTest.render(source, !demo));
          encoder.submit();
          for (int frame = 0; frame < frames; frame++) {
            present(source);
            assertNoGlError();
            byte[] actual = readPixels(source.width, source.height, null);
            encoder.submit();
            TextureTarget expected = reference(source);
            byte[] pixels =
                readPixels(
                    source.width,
                    source.height,
                    (GlTexture) Objects.requireNonNull(expected.getColorTexture()));
            encoder.submit();
            if (!Arrays.equals(actual, pixels)) {
              throw new AssertionError(
                  "Window pixels differ from the offscreen pass at frame "
                      + frame
                      + ", byte "
                      + Arrays.mismatch(actual, pixels));
            }
          }
        });
  }

  private static void present(TextureTarget source) {
    try {
      String owner = "com.qb20nh.cbbg.render.CbbgDither";
      String clientOwner = "com.qb20nh.cbbg.CbbgClient";
      String modeName = "com.qb20nh.cbbg.config.CbbgConfig$Mode";
      Class<?> renderer = Class.forName(ReleaseMapping.className(owner));
      Class<?> modeType = Class.forName(ReleaseMapping.className(modeName));
      Object mode =
          Objects.requireNonNull(
              Class.forName(ReleaseMapping.className(clientOwner))
                  .getMethod(
                      ReleaseMapping.memberName(clientOwner, modeName + " getEffectiveMode()"))
                  .invoke(null));
      var backend = CommandEncoder.class.getDeclaredMethod("backend");
      backend.setAccessible(true);
      var encoder =
          (GlCommandEncoder)
              Objects.requireNonNull(
                  backend.invoke(RenderSystem.getDevice().createCommandEncoder()));
      var device = GlCommandEncoder.class.getDeclaredField("device");
      device.setAccessible(true);
      String signature =
          "boolean blitToWindow(com.mojang.blaze3d.textures.GpuTextureView,"
              + modeName
              + ",com.mojang.blaze3d.opengl.GlCommandEncoder,"
              + "com.mojang.blaze3d.opengl.GlDevice,int,int)";
      Object rendered =
          renderer
              .getMethod(
                  ReleaseMapping.memberName(owner, signature),
                  GpuTextureView.class,
                  modeType,
                  GlCommandEncoder.class,
                  GlDevice.class,
                  int.class,
                  int.class)
              .invoke(
                  null,
                  Objects.requireNonNull(source.getColorTextureView()),
                  mode,
                  encoder,
                  Objects.requireNonNull(device.get(encoder)),
                  source.width,
                  source.height);
      if (!Boolean.TRUE.equals(rendered)) {
        throw new AssertionError("Native window pass was unavailable on the RGBA8 test window");
      }
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot call the packaged window pass", failure);
    }
  }

  private static TextureTarget reference(TextureTarget source) {
    return screenshotOutput(Objects.requireNonNull(source.getColorTextureView()));
  }

  static TextureTarget screenshotOutput(GpuTextureView input) {
    try {
      String owner = "com.qb20nh.cbbg.render.CbbgDither";
      return (TextureTarget)
          Objects.requireNonNull(
              Class.forName(ReleaseMapping.className(owner))
                  .getMethod(
                      ReleaseMapping.memberName(
                          owner,
                          "com.mojang.blaze3d.pipeline.TextureTarget"
                              + " renderScreenshotTarget(com.mojang.blaze3d.textures.GpuTextureView)"),
                      GpuTextureView.class)
                  .invoke(null, input));
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot call the packaged screenshot pass", failure);
    }
  }

  private static byte[] readPixels(
      int width, int height, @org.jspecify.annotations.Nullable GlTexture texture) {
    int framebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    int buffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
    int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
    int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
    int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
    int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
    int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
    ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
    try {
      GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
      GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
      GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
      GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
      if (texture == null) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        int windowBuffer = GL11.glGetInteger(GL11.GL_DRAW_BUFFER);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        GL11.glReadBuffer(windowBuffer);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
      } else {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.glId());
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
      }
      assertNoGlError();
      byte[] result = new byte[pixels.remaining()];
      pixels.get(result);
      return result;
    } finally {
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
      GL11.glReadBuffer(buffer);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
      GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
      GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
      GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
      GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
    }
  }

  private static void assertNoGlError() {
    int error = GL11.glGetError();
    if (error != GL11.GL_NO_ERROR) {
      throw new AssertionError("Window pixel check generated OpenGL error " + error);
    }
  }
}
