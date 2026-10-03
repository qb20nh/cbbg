package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Retains shader and mod preferences between the prepare and verify JVMs. */
@NullMarked
public final class ReleaseIrisRestartGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    String phase = Objects.requireNonNull(System.getProperty("cbbg.test.restart"));
    if (!phase.equals("prepare") && !phase.equals("verify") && !phase.equals("control")) {
      throw new AssertionError("Iris restart requires a prepare, verify or control phase");
    }
    if (!FabricLoader.getInstance().isModLoaded("iris"))
      throw new AssertionError("Iris is missing");
    if (phase.equals("verify")) {
      context.runOnClient(
          client -> {
            Object config = Objects.requireNonNull(IrisFixture.iris("getIrisConfig"));
            try {
              if (!Boolean.TRUE.equals(
                      config.getClass().getMethod("areShadersEnabled").invoke(config))
                  || !Optional.of("cbbg-parity")
                      .equals(config.getClass().getMethod("getShaderPackName").invoke(config))) {
                throw new AssertionError("Iris shader preferences changed across restart");
              }
            } catch (ReflectiveOperationException error) {
              throw new LinkageError("Cannot inspect saved Iris preferences", error);
            }
          });
      try (var reader =
          Files.newBufferedReader(FabricLoader.getInstance().getConfigDir().resolve("cbbg.json"))) {
        var saved = JsonParser.parseReader(reader).getAsJsonObject();
        if (!saved.get("mode").getAsString().equals("ENABLED")
            || !saved.get("pixelFormat").getAsString().equals("RGBA16F")) {
          throw new AssertionError("CBBG preferences changed across restart");
        }
      } catch (java.io.IOException error) {
        throw new AssertionError("Cannot read saved mod preferences", error);
      }
    } else {
      IrisFixture.installPack();
    }
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      if (!phase.equals("verify")) {
        context.runOnClient(
            client -> {
              var dispatcher = Objects.requireNonNull(ClientCommands.getActiveDispatcher());
              var source = ReleaseGenerationGameTest.silentSource();
              ReleaseGenerationGameTest.command(
                  dispatcher,
                  source,
                  "mode set " + (phase.equals("control") ? "disabled" : "enabled"));
              ReleaseGenerationGameTest.command(dispatcher, source, "format set rgba16f");
              IrisFixture.invoke(
                  Objects.requireNonNull(IrisFixture.iris("getIrisConfig")),
                  "setShaderPackName",
                  String.class,
                  "cbbg-parity");
              shaders(true);
            });
      }
      context.waitFor(client -> active() && mainFormat() == GL11.GL_RGBA8, 600);
      long before = ReleaseStartupObservations.draws();
      context.waitTicks(5);
      if (ReleaseStartupObservations.draws() != before
          || Boolean.TRUE.equals(IrisFixture.iris("isFallback"))) {
        throw new AssertionError("CBBG rendered with an active Iris shaderpack");
      }
      String screenshot = "iris-restart-" + phase;
      var path = context.takeScreenshot(screenshot);
      try {
        try (NativeImage image = NativeImage.read(Files.readAllBytes(path))) {
          IrisFixture.assertFinalShader(
              image,
              "Iris final shader missing after restart",
              image.getHeight() / 5,
              image.getHeight() / 2);
        }
      } catch (java.io.IOException error) {
        throw new AssertionError("Cannot inspect restart screenshot", error);
      }
      if (phase.equals("verify")) {
        context.runOnClient(client -> shaders(false));
        context.waitFor(client -> !active() && mainFormat() == GL30.GL_RGBA16F, 600);
        context.waitFor(client -> ReleaseStartupObservations.draws() > before, 600);
      }
    }
  }

  private static void shaders(boolean enabled) {
    IrisFixture.invoke(
        Objects.requireNonNull(IrisFixture.iris("getIrisConfig")),
        "setShadersEnabled",
        boolean.class,
        enabled);
    IrisFixture.saveAndReload();
  }

  private static boolean active() {
    try {
      Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
      return Boolean.TRUE.equals(
          api.getMethod("isShaderPackInUse").invoke(api.getMethod("getInstance").invoke(null)));
    } catch (ReflectiveOperationException error) {
      throw new LinkageError("Cannot inspect Iris shader state", error);
    }
  }

  private static int mainFormat() {
    var texture =
        Objects.requireNonNull(
            ReleaseViewport.mainTarget(net.minecraft.client.Minecraft.getInstance())
                .getColorTexture());
    if (!(texture instanceof GlTexture gl))
      throw new AssertionError("Iris requires an OpenGL texture");
    int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, gl.glId());
      return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
    } finally {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
    }
  }
}
