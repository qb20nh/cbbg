package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.slf4j.LoggerFactory;

@NullMarked
public final class ReleaseLifecycleGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    if (FabricLoader.getInstance().isModLoaded("sulkan")) {
      ReleaseSulkanGameTest.setShadersEnabled(context, false);
    }
    context.runOnClient(
        client -> {
          var info = RenderSystem.getDevice().getDeviceInfo();
          String expected = Objects.requireNonNull(System.getProperty("cbbg.test.backend"));
          if (!info.backendName().equalsIgnoreCase(expected)) {
            throw new AssertionError("Wrong backend: " + info.backendName());
          }
          LoggerFactory.getLogger("cbbg-renderer-test")
              .info(
                  "Readback backend={} GPU={} driver={}",
                  info.backendName(),
                  info.name(),
                  info.driverInfo());
          JsonObject record = new JsonObject();
          record.addProperty("backend", expected);
          record.addProperty("device", info.name());
          record.addProperty("driver", info.driverInfo());
          if (expected.equals("opengl")) {
            var capabilities = GL.getCapabilities();
            int major = GL11.glGetInteger(GL30.GL_MAJOR_VERSION);
            int minor = GL11.glGetInteger(GL30.GL_MINOR_VERSION);
            record.addProperty("version", GL11.glGetString(GL11.GL_VERSION));
            record.addProperty("major", major);
            record.addProperty("minor", minor);
            record.addProperty("flags", GL11.glGetInteger(GL30.GL_CONTEXT_FLAGS));
            int profile =
                capabilities.OpenGL32 ? GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK) : 0;
            record.addProperty("profileMask", profile);
            record.addProperty(
                "profile",
                (profile & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0 ? "core" : "compatibility");
            record.addProperty("glslVersion", GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION));
            record.addProperty("directStateAccess", capabilities.GL_ARB_direct_state_access);
            record.addProperty("bufferStorage", capabilities.GL_ARB_buffer_storage);
            if ("forcegl3".equals(System.getProperty("cbbg.test.compat", "none"))
                && (major != 3 || minor != 0)) {
              throw new AssertionError("ForceGL3 did not provide an OpenGL 3.0 context");
            }
          }
          Path directory =
              Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
          try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("graphics-context.json"), record + "\n");
          } catch (IOException failure) {
            throw new AssertionError(failure);
          }
        });
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      command(context, "mode set disabled");
      awaitFormat(context, GpuFormat.RGBA8_UNORM);
      command(context, "stbn size 16");
      command(context, "stbn depth 8");
      command(context, "stbn seed 42");
      command(context, "stbn generate");
      command(context, "format set rgba16f");
      command(context, "mode set enabled");
      awaitFormat(context, GpuFormat.RGBA16_FLOAT);
      context.waitTicks(10);
      context.takeScreenshot("release-enabled-rgba16f");
      GpuTexture previous =
          context.computeOnClient(
              client ->
                  Objects.requireNonNull(client.gameRenderer.mainRenderTarget().getColorTexture()));
      command(context, "format set rgba32f");
      awaitFormat(context, GpuFormat.RGBA32_FLOAT);
      checkScreenshotFrame(context);
      context.runOnClient(
          client -> {
            if (!previous.isClosed()) throw new AssertionError("Old color texture is still open");
          });
      command(context, "mode set demo");
      context.waitTicks(10);
      context.takeScreenshot("release-demo-rgba32f");
      checkScreenshotFrame(context);
      int[] size =
          context.computeOnClient(
              client -> new int[] {client.getWindow().getWidth(), client.getWindow().getHeight()});
      try {
        context.getInput().resizeWindow(854, 480);
        context.waitFor(
            client ->
                client.gameRenderer.mainRenderTarget().width == 854
                    && client.gameRenderer.mainRenderTarget().height == 480,
            600);
        awaitFormat(context, GpuFormat.RGBA32_FLOAT);
        context.takeScreenshot("release-resized");
      } finally {
        context.getInput().resizeWindow(size[0], size[1]);
        context.waitFor(
            client ->
                client.gameRenderer.mainRenderTarget().width == size[0]
                    && client.gameRenderer.mainRenderTarget().height == size[1],
            600);
      }
      var reload = context.computeOnClient(client -> client.reloadResourcePacks());
      context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 600);
      reload.join();
      awaitFormat(context, GpuFormat.RGBA32_FLOAT);
      if (FabricLoader.getInstance().isModLoaded("modmenu")) {
        context.setScreen(() -> com.terraformersmc.modmenu.ModMenu.getConfigScreen("cbbg", null));
        context.waitFor(client -> client.gui.screen() != null, 600);
        context.waitTicks(3);
        context.takeScreenshot("release-modmenu");
        context.runOnClient(client -> client.gui.setScreen(null));
      }
      if (FabricLoader.getInstance().isModLoaded("renderscale")) {
        float original =
            context.computeOnClient(client -> RenderScaleTestAccess.setShaderTestScale(0.5f));
        try {
          context.waitFor(
              client -> {
                try {
                  Class<?> api = Class.forName("dev.zelo.renderscale.RenderScale");
                  Object instance = api.getMethod("getInstance").invoke(null);
                  RenderTarget target =
                      (RenderTarget)
                          Objects.requireNonNull(api.getField("renderTarget").get(instance));
                  RenderTarget main = client.gameRenderer.mainRenderTarget();
                  return Math.abs(target.width - main.width / 2) <= 1
                      && Math.abs(target.height - main.height / 2) <= 1
                      && matchesFormat(
                          Objects.requireNonNull(target.getColorTexture()), GpuFormat.RGBA32_FLOAT);
                } catch (ReflectiveOperationException failure) {
                  throw new LinkageError("RenderScale API changed", failure);
                }
              },
              600);
          context.takeScreenshot("release-renderscale-half");
        } finally {
          context.runOnClient(client -> RenderScaleTestAccess.setShaderTestScale(original));
        }
      }
      if (FabricLoader.getInstance().isModLoaded("iris")) {
        IrisFixture.installPack();
        context.runOnClient(
            client -> {
              IrisFixture.invoke(
                  Objects.requireNonNull(IrisFixture.iris("getIrisConfig")),
                  "setShaderPackName",
                  String.class,
                  "cbbg-parity");
              shaders(true);
            });
        try {
          context.waitFor(client -> shaderActive(), 600);
          awaitFormat(context, GpuFormat.RGBA8_UNORM);
          context.runOnClient(
              client -> {
                if (Boolean.TRUE.equals(IrisFixture.iris("isFallback"))) {
                  throw new AssertionError("Iris shaderpack fell back");
                }
              });
          context.takeScreenshot("release-iris-shader");
          Path screenshots = FabricLoader.getInstance().getGameDir().resolve("screenshots");
          try (var files = Files.list(screenshots)) {
            Path screenshot =
                files
                    .filter(path -> path.toString().contains("release-iris-shader"))
                    .findFirst()
                    .orElseThrow();
            try (var stream = Files.newInputStream(screenshot);
                NativeImage image = NativeImage.read(stream)) {
              IrisFixture.assertFinalShader(
                  image, "Iris final shader missing", image.getHeight() / 5, image.getHeight() / 2);
            }
          } catch (IOException failure) {
            throw new AssertionError(failure);
          }
        } finally {
          context.runOnClient(client -> shaders(false));
        }
        context.waitFor(client -> !shaderActive(), 600);
        awaitFormat(context, GpuFormat.RGBA32_FLOAT);
      }
      command(context, "mode set disabled");
      awaitFormat(context, GpuFormat.RGBA8_UNORM);
      context.takeScreenshot("release-disabled");
    }
  }

  private static void checkScreenshotFrame(ClientGameTestContext context) {
    context.waitFor(
        client -> {
          String frame = noiseFrame(client);
          return frame.endsWith("/8") && !frame.equals("0/8");
        },
        600);
    context.runOnClient(
        client -> {
          String before = noiseFrame(client);
          Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), NativeImage::close);
          String after = noiseFrame(client);
          if (!after.equals(before)) {
            throw new AssertionError(
                "Taking a screenshot changed the displayed noise frame: "
                    + before
                    + " -> "
                    + after);
          }
        });
  }

  private static String noiseFrame(Minecraft client) {
    var match =
        java.util.regex.Pattern.compile("stbn=(\\d+/\\d+)")
            .matcher(String.join("\n", ReleaseDebugOverlayGameTest.output(client)));
    if (!match.find()) throw new AssertionError("Missing displayed noise frame");
    return Objects.requireNonNull(match.group(1));
  }

  static void command(ClientGameTestContext context, String command) {
    context.runOnClient(
        client -> Objects.requireNonNull(client.getConnection()).sendCommand("cbbg " + command));
  }

  private static void awaitFormat(ClientGameTestContext context, GpuFormat expected) {
    context.waitFor(
        client ->
            matchesFormat(
                Objects.requireNonNull(client.gameRenderer.mainRenderTarget().getColorTexture()),
                expected),
        600);
  }

  private static boolean matchesFormat(GpuTexture texture, GpuFormat expected) {
    if (texture instanceof GlTexture gl) {
      int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      try {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, gl.glId());
        int actual =
            GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        int format =
            expected == GpuFormat.RGBA16_FLOAT
                ? GL30.GL_RGBA16F
                : expected == GpuFormat.RGBA32_FLOAT ? GL30.GL_RGBA32F : GL11.GL_RGBA8;
        return actual == format;
      } finally {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
      }
    }
    return texture.getFormat() == expected;
  }

  private static void shaders(boolean enabled) {
    IrisFixture.invoke(
        Objects.requireNonNull(IrisFixture.iris("getIrisConfig")),
        "setShadersEnabled",
        boolean.class,
        enabled);
    IrisFixture.saveAndReload();
  }

  private static boolean shaderActive() {
    try {
      Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
      return Boolean.TRUE.equals(
          api.getMethod("isShaderPackInUse").invoke(api.getMethod("getInstance").invoke(null)));
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Iris API changed", failure);
    }
  }
}
