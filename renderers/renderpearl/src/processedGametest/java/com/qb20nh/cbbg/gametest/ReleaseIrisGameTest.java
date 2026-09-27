package com.qb20nh.cbbg.gametest;

import static com.qb20nh.cbbg.gametest.IrisFixture.*;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Exercises shader suspension through the packaged mod's commands and debug entry. */
@NullMarked
public final class ReleaseIrisGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    boolean expected =
        List.of(Objects.requireNonNull(System.getProperty("cbbg.test.compat", "none")).split("\\+"))
            .contains("iris");
    if (FabricLoader.getInstance().isModLoaded("iris") != expected) {
      throw new AssertionError("Iris presence does not match the requested fixture");
    }
    if (!expected) return;
    requireIris(context);
    installPack();
    JsonObject original = ReleaseClient.settings();
    boolean scaled = FabricLoader.getInstance().isModLoaded("renderscale");
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      float originalScale =
          context.computeOnClient(
              client -> scaled ? RenderScaleTestAccess.setShaderTestScale(0.5f) : 1);
      try {
        ReleaseClient.command(context, "mode set enabled");
        ReleaseClient.command(context, "format set rgba16f");
        ReleaseClient.awaitFormat(context, GpuFormat.RGBA16_FLOAT);
        ReleaseClient.awaitDrawAfter(context, ProcessedRenderObservations.draws());
        context.runOnClient(
            client -> {
              invoke(
                  Objects.requireNonNull(iris("getIrisConfig")),
                  "setShaderPackName",
                  String.class,
                  "cbbg-parity");
              setShaders(true);
            });
        context.waitFor(client -> active(), 600);
        ReleaseClient.awaitFormat(context, GpuFormat.RGBA8_UNORM);
        long stopped = ProcessedRenderObservations.draws();
        context.waitTicks(5);
        context.runOnClient(
            client -> {
              if (!ReleaseClient.settings().get("mode").getAsString().equals("ENABLED")
                  || ProcessedRenderObservations.draws() != stopped) {
                throw new AssertionError(
                    "Iris did not suspend CBBG without changing user settings");
              }
              if (Objects.requireNonNull((Boolean) iris("isFallback"))) {
                throw new AssertionError(
                    "Iris fell back instead of rendering the fixture shaderpack");
              }
              checkDebug(client, "ENABLED", true);
              if (scaled)
                RenderScaleTestAccess.assertShaderTargetFormat(
                    client.gameRenderer.mainRenderTarget(), "RGBA8_UNORM");
            });
        captureShader(context, "iris-active-" + System.getProperty("cbbg.test.backend"));
        ReleaseClient.command(context, "mode set demo");
        ReleaseClient.assertNoDraws(context);
        context.runOnClient(client -> checkDebug(client, "DEMO", true));
        context.runOnClient(client -> setShaders(false));
        context.waitFor(client -> !active(), 600);
        ReleaseClient.awaitFormat(context, GpuFormat.RGBA16_FLOAT);
        ReleaseClient.awaitDrawAfter(context, stopped);
        context.runOnClient(
            client -> {
              checkDebug(client, "DEMO", false);
              if (scaled)
                RenderScaleTestAccess.assertShaderTargetFormat(
                    client.gameRenderer.mainRenderTarget(), "RGBA16_FLOAT");
            });
      } finally {
        try {
          context.runOnClient(client -> setShaders(false));
        } finally {
          try {
            ReleaseClient.command(
                context, "mode set " + original.get("mode").getAsString().toLowerCase(Locale.ROOT));
            ReleaseClient.command(
                context,
                "format set " + original.get("pixelFormat").getAsString().toLowerCase(Locale.ROOT));
            context.waitFor(client -> original.equals(ReleaseClient.settings()), 600);
          } finally {
            if (scaled)
              context.runOnClient(
                  client -> RenderScaleTestAccess.setShaderTestScale(originalScale));
          }
        }
      }
    }
  }

  static void requireIris(ClientGameTestContext context) {
    if (!FabricLoader.getInstance().isModLoaded("iris")
        || !FabricLoader.getInstance().isModLoaded("sodium")) {
      throw new AssertionError("Iris fixture requires Iris and Sodium");
    }
    context.runOnClient(
        client -> {
          if (!RenderSystem.getDevice().getDeviceInfo().backendName().equalsIgnoreCase("opengl")) {
            throw new AssertionError("Iris fixture requires actual OpenGL");
          }
        });
  }

  static boolean active() {
    try {
      Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
      return Objects.requireNonNull(
          (Boolean)
              api.getMethod("isShaderPackInUse").invoke(api.getMethod("getInstance").invoke(null)));
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot query Iris shaderpack state", cause(failure));
    }
  }

  static void setShaders(boolean enabled) {
    invoke(
        Objects.requireNonNull(iris("getIrisConfig")), "setShadersEnabled", boolean.class, enabled);
    saveAndReload();
  }

  static void captureShader(ClientGameTestContext context, String name) {
    CompletableFuture<@Nullable Void> capture = new CompletableFuture<>();
    context.runOnClient(
        client ->
            Screenshot.takeScreenshot(
                client.gameRenderer.mainRenderTarget(),
                image -> {
                  try (image) {
                    var pixels =
                        ReleaseClient.centerPixels(image, pixel -> (pixel & 0xffffff) == 0xff00ff);
                    if (pixels.total() == 0 || pixels.matching() < pixels.total() * 0.9) {
                      throw new AssertionError(
                          "The fixture's final shader did not reach the screenshot");
                    }
                    Path evidence =
                        Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
                    Files.createDirectories(evidence);
                    image.writeToFile(evidence.resolve(name + ".png"));
                    capture.complete(null);
                  } catch (Throwable failure) {
                    capture.completeExceptionally(failure);
                  }
                }));
    context.waitFor(client -> capture.isDone(), 200);
    capture.join();
  }

  static void checkDebug(Minecraft client, String user, boolean active) {
    String output = String.join("\n", ReleaseDebugState.read(client));
    String effective = active ? "DISABLED" : user;
    String format = active ? "RGBA8_UNORM" : "RGBA16_FLOAT";
    var frame = Pattern.compile("stbn=(\\d+)/(\\d+)").matcher(output);
    int depth = ReleaseClient.settings().get("stbnDepth").getAsInt();
    boolean frames =
        frame.find()
            && (active
                ? Objects.requireNonNull(frame.group(1)).equals("0")
                    && Objects.requireNonNull(frame.group(2)).equals("0")
                : Integer.parseInt(Objects.requireNonNull(frame.group(2))) == depth
                    && Integer.parseInt(Objects.requireNonNull(frame.group(1))) < depth);
    if (!output.contains("mode=" + effective + " (user=" + user + ")")
        || !output.contains("iris=" + (active ? 1 : 0))
        || !output.contains("dis=0")
        || !output.contains("main=" + format)
        || !frames) {
      throw new AssertionError("Iris debug state differs from the render state: " + output);
    }
    try {
      Path evidence =
          Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")), "debug");
      Files.createDirectories(evidence);
      Files.writeString(evidence.resolve("iris-" + active + "-" + user + ".txt"), output + "\n");
    } catch (java.io.IOException failure) {
      throw new AssertionError("Could not retain Iris debug output", failure);
    }
  }
}
