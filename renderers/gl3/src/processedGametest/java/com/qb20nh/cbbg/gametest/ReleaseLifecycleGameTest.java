package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexBuffer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Ordinary packaged lifecycle checks; specialized release suites remain separate. */
@NullMarked
public final class ReleaseLifecycleGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    try (var world = context.worldBuilder().create()) {
      ReleaseWorldAccess.waitForChunksRender(world);
      command(context, "mode set disabled");
      command(context, "stbn size 16");
      command(context, "stbn depth 8");
      command(context, "stbn seed 42");
      command(context, "format set rgba16f");
      awaitFormat(context, GL11.GL_RGBA8);
      noPresentations(context);
      command(context, "stbn generate");
      command(context, "mode set enabled");
      awaitFormat(context, GL30.GL_RGBA16F);
      awaitPresentation(context);
      ReleasePixels.check(context, false);
      checkScreenQuadLifecycle(context);
      ReleaseNoiseTextures.check(context);
      ReleaseFallbackScreenshots.check(context);
      screenshot(context, "enabled-rgba16f");

      command(context, "format set rgba32f");
      awaitFormat(context, GL30.GL_RGBA32F);
      command(context, "mode set demo");
      awaitPresentation(context);
      ReleasePixels.check(context, true);
      screenshot(context, "demo-rgba32f");
      ReleaseScreenPassResources.check(context);

      int[] original =
          context.computeOnClient(
              client -> new int[] {client.getWindow().getWidth(), client.getWindow().getHeight()});
      try {
        context.runOnClient(
            client -> GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 854, 480));
        context.waitFor(
            client ->
                client.getMainRenderTarget().width == 854
                    && client.getMainRenderTarget().height == 480,
            600);
        awaitFormat(context, GL30.GL_RGBA32F);
        awaitPresentation(context);
        screenshot(context, "resized");
      } finally {
        context.runOnClient(
            client ->
                GLFW.glfwSetWindowSize(client.getWindow().getWindow(), original[0], original[1]));
        context.waitFor(
            client ->
                client.getMainRenderTarget().width == original[0]
                    && client.getMainRenderTarget().height == original[1],
            600);
      }
      var reload = context.computeOnClient(client -> client.reloadResourcePacks());
      context.waitFor(client -> reload.isDone() && client.getOverlay() == null, 600);
      reload.join();
      awaitPresentation(context);
      screenshot(context, "reloaded");
      var output =
          context.computeOnClient(client -> Objects.requireNonNull(ReleaseObservations.output()));
      command(context, "mode set disabled");
      awaitFormat(context, GL11.GL_RGBA8);
      noPresentations(context);
      context.runOnClient(
          client -> {
            if (output.getColorTextureId() > 0 || output.frameBufferId >= 0) {
              throw new AssertionError("Disabling CBBG retained its output texture");
            }
          });
      command(context, "mode set enabled");
      awaitFormat(context, GL30.GL_RGBA32F);
      awaitPresentation(context);
    }
    try (var world = context.worldBuilder().create()) {
      ReleaseWorldAccess.waitForChunksRender(world);
      awaitPresentation(context);
      screenshot(context, "world-transition");
    }
  }

  @SuppressWarnings("ReferenceEquality") // A replacement must own a new GPU buffer.
  private static void checkScreenQuadLifecycle(ClientGameTestContext context) {
    if (!"none".equals(System.getProperty("cbbg.test.compat", "none"))) return;
    VertexBuffer previous =
        context.computeOnClient(
            client -> {
              VertexBuffer quad = screenQuad();
              int previousArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
              int foreignArray = GL30.glGenVertexArrays();
              try {
                quad.bind();
                int array = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
                int vertices =
                    GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING);
                if (quad.isInvalid()
                    || !GL30.glIsVertexArray(array)
                    || !GL15.glIsBuffer(vertices)) {
                  throw new AssertionError("Screen quad was not uploaded");
                }
                BufferUploader.invalidate();
                GlStateManager._glBindVertexArray(foreignArray);
                String owner = "com.qb20nh.cbbg.render.DitherController";
                var close =
                    Class.forName(ReleaseMapping.className(owner))
                        .getDeclaredMethod(ReleaseMapping.memberName(owner, "void close()"));
                close.setAccessible(true);
                close.invoke(null);
                if (GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) != foreignArray
                    || !GL30.glIsVertexArray(foreignArray)) {
                  throw new AssertionError("Screen quad close changed a foreign VAO");
                }
                if (!quad.isInvalid()
                    || GL30.glIsVertexArray(array)
                    || GL15.glIsBuffer(vertices)
                    || ReleaseShutdownGameTest.field(
                            "com.qb20nh.cbbg.render.DitherPresentation",
                            null,
                            "com.mojang.blaze3d.vertex.VertexBuffer screenQuad")
                        != null) {
                  throw new AssertionError("Screen quad close retained owned resources");
                }
                return quad;
              } catch (ReflectiveOperationException error) {
                throw new LinkageError("Cannot close packaged screen quad", error);
              } finally {
                BufferUploader.invalidate();
                GlStateManager._glBindVertexArray(
                    GL30.glIsVertexArray(previousArray) ? previousArray : 0);
                GL30.glDeleteVertexArrays(foreignArray);
              }
            });
    awaitPresentation(context);
    context.runOnClient(
        client -> {
          VertexBuffer recreated = screenQuad();
          if (recreated == previous || recreated.isInvalid()) {
            throw new AssertionError("Screen quad was not recreated after close");
          }
          System.out.println("CBBG_SCREEN_QUAD_LIFECYCLE_OK");
        });
  }

  private static VertexBuffer screenQuad() {
    return (VertexBuffer)
        Objects.requireNonNull(
            ReleaseShutdownGameTest.field(
                "com.qb20nh.cbbg.render.DitherPresentation",
                null,
                "com.mojang.blaze3d.vertex.VertexBuffer screenQuad"));
  }

  static void command(ClientGameTestContext context, String command) {
    context.runOnClient(
        client -> Objects.requireNonNull(client.getConnection()).sendCommand("cbbg " + command));
  }

  static void awaitPresentation(ClientGameTestContext context) {
    long count = ReleaseObservations.presentations();
    context.waitFor(client -> ReleaseObservations.presentations() > count, 600);
  }

  static void noPresentations(ClientGameTestContext context) {
    context.waitTicks(3);
    long count = ReleaseObservations.presentations();
    context.waitTicks(5);
    if (ReleaseObservations.presentations() != count) {
      throw new AssertionError("CBBG presented while disabled");
    }
  }

  static void awaitFormat(ClientGameTestContext context, int expected) {
    context.waitFor(
        client -> format(client.getMainRenderTarget().getColorTextureId()) == expected, 600);
  }

  static int format(int texture) {
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      GlStateManager._bindTexture(texture);
      return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }

  private static void screenshot(ClientGameTestContext context, String name) {
    context.runOnClient(
        client -> {
          try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
            if (image.getWidth() < 1 || image.getHeight() < 1) {
              throw new AssertionError("Empty screenshot");
            }
            Path directory =
                Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
            Files.createDirectories(directory);
            image.writeToFile(directory.resolve(name + ".png"));
          } catch (IOException failure) {
            throw new AssertionError("Cannot save lifecycle screenshot", failure);
          }
        });
  }
}
