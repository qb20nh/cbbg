package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.GlStateManager;
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
      ReleaseFallbackScreenshots.check(context);
      screenshot(context, "enabled-rgba16f");

      command(context, "format set rgba32f");
      awaitFormat(context, GL30.GL_RGBA32F);
      command(context, "mode set demo");
      awaitPresentation(context);
      ReleasePixels.check(context, true);
      screenshot(context, "demo-rgba32f");

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
