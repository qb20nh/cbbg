package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Exercises a real packaged shader failure through Minecraft's screenshot path. */
@NullMarked
public final class ReleaseShaderFailureGameTest implements FabricClientGameTest {
  private static final int WAIT_TICKS = 600;
  private static final int RED = 0xffff0000;

  @Override
  public void runTest(ClientGameTestContext context) {
    JsonObject original = ReleaseClient.settings();
    try (var world = context.worldBuilder().create()) {
      try {
        world.getConnection().waitForChunksRender();
        long beforeEnable = ProcessedRenderObservations.draws();
        ReleaseClient.command(context, "mode set enabled");
        GpuFormat format =
            original.get("pixelFormat").getAsString().equals("RGBA32F")
                ? GpuFormat.RGBA32_FLOAT
                : GpuFormat.RGBA16_FLOAT;
        ReleaseClient.awaitFormat(context, format);
        context.waitFor(client -> client.gui.overlay() == null, WAIT_TICKS);
        ReleaseClient.awaitDrawAfter(context, beforeEnable);
        String settingsBeforeFailure = settingsJson();

        CompletableFuture<@Nullable Void> healthyCapture = new CompletableFuture<>();
        Healthy healthy =
            context.computeOnClient(
                client -> {
                  var main = client.gameRenderer.mainRenderTarget();
                  GpuTexture input = Objects.requireNonNull(main.getColorTexture());
                  RenderSystem.getDevice()
                      .createCommandEncoder()
                      .clearColorTexture(input, new Vector4f(1, 0, 0, 1));
                  long draws = ProcessedRenderObservations.draws();
                  Screenshot.takeScreenshot(
                      main,
                      1,
                      image -> {
                        try (image) {
                          healthyCapture.complete(null);
                        } catch (Throwable failure) {
                          healthyCapture.completeExceptionally(failure);
                        }
                      });
                  var output = ProcessedRenderObservations.lastDitherOutput();
                  var noise = ProcessedRenderObservations.lastDitherNoise();
                  if (ProcessedRenderObservations.draws() <= draws
                      || output == null
                      || noise == null
                      || output.texture().isClosed()
                      || noise.texture().isClosed()) {
                    throw new AssertionError(
                        "Healthy screenshot did not draw with live CBBG resources");
                  }
                  return new Healthy(input, output.texture(), noise.texture());
                });
        await(context, healthyCapture);

        CompletableFuture<@Nullable Void> fallbackCapture = new CompletableFuture<>();
        context.runOnClient(
            client -> failShader(client, healthy, settingsBeforeFailure, fallbackCapture));
        await(context, fallbackCapture);
        ReleaseClient.assertNoDraws(context);

        ReleaseClient.command(context, "mode set disabled");
        context.waitFor(
            client -> "DISABLED".equals(ReleaseClient.settings().get("mode").getAsString()),
            WAIT_TICKS);
        ReleaseClient.assertNoDraws(context);
        long beforeRecovery = ProcessedRenderObservations.draws();
        ReleaseClient.command(context, "mode set enabled");
        ReleaseClient.awaitFormat(context, format);
        ReleaseClient.awaitDrawAfter(context, beforeRecovery);
        context.runOnClient(
            client -> {
              List<String> lines = ReleaseDebugState.read(client);
              if (lines.stream().noneMatch(line -> line.contains(" dis=0"))) {
                throw new AssertionError(
                    "Shader failure marker survived disable/re-enable: " + lines);
              }
            });
      } finally {
        ReleaseClient.command(context, "mode set disabled");
        context.waitTicks(3);
        ReleaseClient.command(
            context, "mode set " + original.get("mode").getAsString().toLowerCase(Locale.ROOT));
        context.waitFor(
            client ->
                original
                    .get("mode")
                    .getAsString()
                    .equals(ReleaseClient.settings().get("mode").getAsString()),
            WAIT_TICKS);
      }
    }
  }

  // Exact input texture identity verifies preservation through shader failure.
  @SuppressWarnings("ReferenceEquality")
  private static void failShader(
      net.minecraft.client.Minecraft client,
      Healthy healthy,
      String settingsBeforeFailure,
      CompletableFuture<@Nullable Void> fallbackCapture) {
    var main = client.gameRenderer.mainRenderTarget();
    if (main.getColorTexture() != healthy.input) {
      throw new AssertionError("Main screenshot input changed before shader failure");
    }
    PipelineCache fallback =
        (PipelineCache)
            Objects.requireNonNull(field(RenderSystem.class, null, "fallbackPipelineCache"));
    PipelineCache current = (PipelineCache) field(RenderSystem.class, null, "currentPipelineCache");
    ShaderSource borrowed =
        (ShaderSource)
            Objects.requireNonNull(
                field(PipelineCache.class, current == null ? fallback : current, "shaderSource"));
    AtomicInteger attempts = new AtomicInteger();
    ShaderSource invalid =
        new ShaderSource() {
          @Override
          public @Nullable String getShader(Identifier id, ShaderType type) {
            String shader = borrowed.getShader(id, type);
            if (id.equals(Identifier.fromNamespaceAndPath("cbbg", "core/cbbg_dither"))) {
              if (shader == null) throw new AssertionError("Original dither shader is missing");
              attempts.incrementAndGet();
              return shader + "\n#error CBBG deliberate shader compilation failure\n";
            }
            return shader;
          }

          @Override
          public @Nullable CachedIncludeSource getInclude(Identifier id) {
            return borrowed.getInclude(id);
          }

          @Override
          public void close() {} // Minecraft's original cache owns the borrowed shader source.
        };
    var broken = new PipelineCache(RenderSystem.getDevice(), invalid);
    var previous = RenderSystem.setCurrentPipelineCache(broken);
    long draws = ProcessedRenderObservations.draws();
    try {
      replaceFallback(broken);
      RenderSystem.getDevice()
          .createCommandEncoder()
          .clearColorTexture(healthy.input, new Vector4f(1, 0, 0, 1));
      Screenshot.takeScreenshot(
          main,
          1,
          image -> {
            try (image) {
              for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                  if (image.getPixel(x, y) != RED) {
                    throw new AssertionError(
                        "Fallback changed main input at "
                            + x
                            + ","
                            + y
                            + ": "
                            + Integer.toHexString(image.getPixel(x, y)));
                  }
                }
              }
              Path evidence =
                  Path.of(
                      Objects.requireNonNull(System.getProperty("cbbg.test.evidence")),
                      "shader-failure");
              Files.createDirectories(evidence);
              image.writeToFile(evidence.resolve("fallback.png"));
              fallbackCapture.complete(null);
            } catch (Throwable failure) {
              fallbackCapture.completeExceptionally(failure);
            }
          });
      List<String> lines = ReleaseDebugState.read(client);
      if (attempts.get() == 0
          || !healthy.output.isClosed()
          || !healthy.noise.isClosed()
          || healthy.input.isClosed()
          || main.getColorTexture() != healthy.input
          || ProcessedRenderObservations.draws() != draws
          || !settingsBeforeFailure.equals(settingsJson())
          || lines.stream().noneMatch(line -> line.contains(" dis=1"))
          || lines.stream().noneMatch(line -> line.contains(" stbn=0/0"))) {
        throw new AssertionError("Shader failure lost state, input, or owned resources: " + lines);
      }
      try {
        Path evidence =
            Path.of(
                Objects.requireNonNull(System.getProperty("cbbg.test.evidence")), "shader-failure");
        Files.createDirectories(evidence);
        Files.writeString(
            evidence.resolve("state.txt"),
            "compilerAttempts=" + attempts.get() + "\n" + String.join("\n", lines) + "\n");
      } catch (IOException failure) {
        throw new AssertionError("Could not retain shader failure evidence", failure);
      }
    } finally {
      try {
        RenderSystem.setCurrentPipelineCache(previous);
      } finally {
        try {
          replaceFallback(fallback);
        } finally {
          broken.close();
        }
      }
    }
  }

  private static void await(
      ClientGameTestContext context, CompletableFuture<@Nullable Void> capture) {
    context.waitFor(client -> capture.isDone(), 200);
    capture.join();
  }

  private static String settingsJson() {
    try {
      return Files.readString(ReleaseClient.config());
    } catch (IOException failure) {
      throw new AssertionError("Could not read CBBG settings", failure);
    }
  }

  private static @Nullable Object field(Class<?> owner, @Nullable Object instance, String name) {
    try {
      Field field = owner.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(instance);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot inspect " + name, failure);
    }
  }

  private static void replaceFallback(PipelineCache cache) {
    try {
      Field field = RenderSystem.class.getDeclaredField("fallbackPipelineCache");
      field.setAccessible(true);
      field.set(null, cache);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot replace the test fallback cache", failure);
    }
  }

  private record Healthy(GpuTexture input, GpuTexture output, GpuTexture noise) {}
}
