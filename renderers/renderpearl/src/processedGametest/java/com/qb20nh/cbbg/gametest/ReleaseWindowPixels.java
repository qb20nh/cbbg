package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/** Compares native-window pixels with the existing RGBA8 pass for the same noise frame. */
@NullMarked
public final class ReleaseWindowPixels implements FabricClientGameTest {
  private static final String OWNER = "com.qb20nh.cbbg.render.DitherPresentation";

  public ReleaseWindowPixels() {}

  @Override
  public void runTest(ClientGameTestContext context) {
    check(context);
  }

  static void check(ClientGameTestContext context) {
    if (!"opengl".equals(System.getProperty("cbbg.test.backend"))) return;
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      String previous = ReleaseClient.settings().get("mode").getAsString();
      try {
        for (String mode : new String[] {"enabled", "demo"}) {
          ReleaseClient.command(context, "mode set " + mode);
          context.waitFor(
              client -> mode.equalsIgnoreCase(ReleaseClient.settings().get("mode").getAsString()),
              600);
          long draws = ProcessedRenderObservations.draws();
          ReleaseClient.awaitDrawAfter(context, draws);
          context.runOnClient(client -> compare());
        }
      } finally {
        ReleaseClient.command(context, "mode set " + previous.toLowerCase(java.util.Locale.ROOT));
        context.waitFor(
            client -> previous.equals(ReleaseClient.settings().get("mode").getAsString()), 600);
      }
    }
  }

  static TextureTarget screenshotOutput(GpuTextureView input) {
    Object output =
        invoke(
            method(
                "com.mojang.blaze3d.pipeline.TextureTarget"
                    + " screenshot(com.mojang.renderpearl.api.textures.GpuTextureView)",
                GpuTextureView.class),
            input);
    if (!(output instanceof TextureTarget target))
      throw new AssertionError("Dither screenshot output is unavailable");
    return target;
  }

  private static void compare() {
    int width = Minecraft.getInstance().getWindow().getWidth();
    int height = Minecraft.getInstance().getWindow().getHeight();
    TextureTarget source =
        new TextureTarget("CBBG window pixels", width, height, GpuFormat.RGBA32_FLOAT, null);
    try {
      RenderSystem.getDevice()
          .createCommandEncoder()
          .clearColorTexture(
              Objects.requireNonNull(source.getColorTexture()),
              new Vector4f(127.25f / 255, 93.75f / 255, 181.5f / 255, 1));
      GpuTextureView input = Objects.requireNonNull(source.getColorTextureView());
      Method window =
          method(
              "boolean presentToWindow(com.mojang.renderpearl.api.textures.GpuTextureView,int,int)",
              GpuTextureView.class,
              int.class,
              int.class);
      Method screenshot =
          method(
              "com.mojang.blaze3d.pipeline.TextureTarget"
                  + " screenshot(com.mojang.renderpearl.api.textures.GpuTextureView)",
              GpuTextureView.class);
      int originalDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      int originalRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      if (!Boolean.FALSE.equals(invoke(window, input, width + 1, height))) {
        throw new AssertionError("Mismatched window dimensions were accepted");
      }
      for (int frame = 0; frame < 8; frame++) {
        checkGl("before direct draw");
        if (!Boolean.TRUE.equals(invoke(window, input, width, height))) {
          throw new AssertionError("Compatible window did not use direct drawing");
        }
        if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != originalDraw
            || GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) != originalRead) {
          throw new AssertionError("Window drawing changed framebuffer bindings");
        }
        checkGl("after direct draw");
        byte[] actual =
            readPixels(
                0,
                width,
                height,
                (Integer) Objects.requireNonNull(invoke(method("int windowDrawBuffer()"))));
        checkGl("after window readback");
        int red = Byte.toUnsignedInt(actual[0]);
        int green = Byte.toUnsignedInt(actual[1]);
        int blue = Byte.toUnsignedInt(actual[2]);
        if (red < 100 || red > 150 || green < 60 || green > 120 || blue < 150 || blue > 220) {
          throw new AssertionError(
              "Window fixture color was not drawn: " + red + "," + green + "," + blue);
        }
        TextureTarget reference = (TextureTarget) Objects.requireNonNull(invoke(screenshot, input));
        Object texture = Objects.requireNonNull(reference.getColorTexture());
        int textureId;
        try {
          Method id = texture.getClass().getMethod("glId");
          id.setAccessible(true);
          textureId = (Integer) Objects.requireNonNull(id.invoke(texture));
        } catch (ReflectiveOperationException failure) {
          throw new LinkageError("Cannot inspect reference texture", failure);
        }
        int framebuffer = GL30.glGenFramebuffers();
        try {
          int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
          try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(
                GL30.GL_READ_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D,
                textureId,
                0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER)
                != GL30.GL_FRAMEBUFFER_COMPLETE) {
              throw new AssertionError("Incomplete reference framebuffer");
            }
          } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
          }
          byte[] expected = readPixels(framebuffer, width, height, GL30.GL_COLOR_ATTACHMENT0);
          if (!Arrays.equals(actual, expected)) {
            throw new AssertionError(
                "Window and RGBA8 pass pixels differ at byte "
                    + Arrays.mismatch(actual, expected)
                    + " in noise frame "
                    + frame);
          }
        } finally {
          GL30.glDeleteFramebuffers(framebuffer);
        }
        originalDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        originalRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      }
      if (GL11.glGetError() != GL11.GL_NO_ERROR)
        throw new AssertionError("Window pixel test caused an OpenGL error");
    } finally {
      source.destroyBuffers();
    }
  }

  private static void checkGl(String stage) {
    int error = GL11.glGetError();
    if (error != GL11.GL_NO_ERROR) throw new AssertionError(stage + ": OpenGL error " + error);
  }

  private static byte[] readPixels(int framebuffer, int width, int height, int buffer) {
    int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    int previousBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
    int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
    int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
    int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
    int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
    int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
    try {
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
      GL11.glReadBuffer(buffer);
      GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
      GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
      GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
      GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
      ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
      GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
      byte[] bytes = new byte[pixels.remaining()];
      pixels.get(bytes);
      return bytes;
    } finally {
      GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
      GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
      GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
      GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
      GL11.glReadBuffer(previousBuffer);
    }
  }

  private static Method method(String signature, Class<?>... arguments) {
    try {
      Class<?> type = Class.forName(ReleaseMapping.className(OWNER));
      Method method =
          type.getDeclaredMethod(ReleaseMapping.memberName(OWNER, signature), arguments);
      method.setAccessible(true);
      return method;
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot find packaged window presentation method", failure);
    }
  }

  private static @Nullable Object invoke(Method method, Object... arguments) {
    try {
      return method.invoke(null, arguments);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error error) throw error;
      throw new AssertionError("Window presentation failed", cause);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot call packaged window presentation", failure);
    }
  }
}
