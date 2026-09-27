package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.qb20nh.cbbg.reference.DitherReference;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Real world pixels versus a CPU oracle and a checked-in, version-specific scene baseline. */
@NullMarked
public final class ReleaseWorldPixelsGameTest implements FabricClientGameTest {
  private static final int NOISE_SIZE = 16;
  private static final int NOISE_DEPTH = 8;
  private static final long NOISE_SEED = 0;

  @Override
  public void runTest(ClientGameTestContext context) {
    runScene(context, false);
  }

  static void runScene(ClientGameTestContext context, boolean disabledControl) {
    boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
    try (var world = context.worldBuilder().create()) {
      String mode =
          context.computeOnClient(
              client -> {
                var match =
                    java.util.regex.Pattern.compile("user=(ENABLED|DISABLED|DEMO)")
                        .matcher(String.join("\n", ReleaseDebugState.read(client)));
                if (!match.find()) throw new AssertionError("Cannot read current user mode");
                return Objects.requireNonNull(match.group(1)).toLowerCase(java.util.Locale.ROOT);
              });
      // Persist defaults before saving settings from a standalone, minimal startup config.
      ReleaseClient.command(context, "mode set " + mode);
      JsonObject original = ReleaseClient.settings();
      try {
        var server = world.getServer();
        server.runCommand("gamemode spectator @a");
        server.runCommand("time set 6000");
        server.runCommand("weather clear");
        server.runCommand("fill -8 -64 5 8 -50 5 minecraft:white_concrete");
        server.runCommand("fill -8 -64 5 -3 -55 5 minecraft:red_concrete");
        server.runCommand("fill 2 -57 5 8 -50 5 minecraft:blue_concrete");
        server.runCommand("setblock -1 -56 5 minecraft:glowstone");
        server.runCommand("tp @a 0.5 -58 0.5 0 0");
        world.getConnection().waitForChunksRender();
        context.runOnClient(
            client -> {
              if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            });
        // The checked-in world images were captured at this strength. The packaged
        // command API cannot change it after startup, so fail rather than compare
        // against an image made with different production uniforms.
        if (ReleaseClient.settings().get("strength").getAsFloat() != 2.0f) {
          throw new AssertionError("World reference requires startup CBBG strength 2");
        }
        ReleaseClient.command(context, "mode set disabled");
        ReleaseClient.command(context, "stbn size " + NOISE_SIZE);
        ReleaseClient.command(context, "stbn depth " + NOISE_DEPTH);
        ReleaseClient.command(context, "stbn seed " + NOISE_SEED);
        ReleaseClient.command(context, "format set rgba32f");
        ReleaseClient.command(context, "stbn generate");
        ReleaseClient.awaitCache(context, NOISE_SIZE, NOISE_DEPTH, NOISE_SEED);
        ReleaseClient.command(context, "mode set " + (disabledControl ? "disabled" : "enabled"));
        ReleaseClient.awaitFormat(
            context, disabledControl ? GpuFormat.RGBA8_UNORM : GpuFormat.RGBA32_FLOAT);
        ReleaseClient.assertSettings(
            disabledControl ? "DISABLED" : "ENABLED",
            "RGBA32F",
            NOISE_SIZE,
            NOISE_DEPTH,
            NOISE_SEED);
        context.waitFor(client -> client.gui.overlay() == null, 600);
        context.waitTicks(20);
        if (disabledControl) {
          ReleaseClient.assertNoDraws(context);
          captureDisabled(context);
        } else {
          CompletionException firstFailure = null;
          for (boolean demo : new boolean[] {false, true}) {
            try {
              capture(context, demo);
            } catch (CompletionException failure) {
              // Retain both modes for diagnosis, without turning a failed capture into a pass.
              if (firstFailure == null) firstFailure = failure;
              else firstFailure.addSuppressed(failure);
            }
          }
          if (firstFailure != null) throw firstFailure;
        }
      } finally {
        ReleaseClient.command(context, "mode set disabled");
        ReleaseClient.command(context, "stbn size " + original.get("stbnSize").getAsInt());
        ReleaseClient.command(context, "stbn depth " + original.get("stbnDepth").getAsInt());
        ReleaseClient.command(context, "stbn seed " + original.get("stbnSeed").getAsLong());
        ReleaseClient.command(
            context,
            "format set "
                + original.get("pixelFormat").getAsString().toLowerCase(java.util.Locale.ROOT));
        ReleaseClient.command(
            context,
            "mode set " + original.get("mode").getAsString().toLowerCase(java.util.Locale.ROOT));
        context.runOnClient(
            client -> {
              if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
            });
      }
    }
  }

  private static void captureDisabled(ClientGameTestContext context) {
    CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
    context.runOnClient(
        client -> {
          if (!"DISABLED".equals(ReleaseClient.settings().get("mode").getAsString())) {
            throw new AssertionError("World control must keep CBBG disabled");
          }
          Screenshot.takeScreenshot(
              client.gameRenderer.mainRenderTarget(),
              image -> {
                try (image) {
                  Path directory =
                      Path.of(
                          Objects.requireNonNull(System.getProperty("cbbg.test.evidence")),
                          "world-disabled");
                  Files.createDirectories(directory);
                  image.writeToFile(directory.resolve("actual.png"));
                  result.complete(null);
                } catch (Throwable failure) {
                  result.completeExceptionally(failure);
                }
              });
        });
    context.waitFor(client -> result.isDone(), 200);
    result.join();
  }

  private static void capture(ClientGameTestContext context, boolean demo) {
    long beforeMode = ProcessedRenderObservations.draws();
    ReleaseClient.command(context, "mode set " + (demo ? "demo" : "enabled"));
    ReleaseClient.awaitFormat(context, GpuFormat.RGBA32_FLOAT);
    ReleaseClient.awaitDrawAfter(context, beforeMode);
    ReleaseClient.assertSettings(
        demo ? "DEMO" : "ENABLED", "RGBA32F", NOISE_SIZE, NOISE_DEPTH, NOISE_SEED);
    CompletableFuture<float[]> source = new CompletableFuture<>();
    CompletableFuture<int[]> actual = new CompletableFuture<>();
    CompletableFuture<@Nullable Void> result =
        context.computeOnClient(
            client -> {
              try {
                Path noisePath =
                    ReleaseClient.cache()
                        .resolve(
                            "stbn_" + NOISE_SIZE + "x" + NOISE_SIZE + "x" + NOISE_DEPTH + "_0.png");
                NativeImage noiseImage;
                try (var input = Files.newInputStream(noisePath)) {
                  noiseImage = NativeImage.read(input);
                }
                int[] noise;
                int tileSize;
                GpuTexture noiseTexture;
                GpuTextureView noiseView;
                try (noiseImage) {
                  noise = noiseImage.getPixels();
                  tileSize = noiseImage.getWidth();
                  if (tileSize != NOISE_SIZE || noiseImage.getHeight() != NOISE_SIZE) {
                    throw new AssertionError("Generated noise frame dimensions changed");
                  }
                  var device = RenderSystem.getDevice();
                  noiseTexture =
                      device.createTexture(
                          "CBBG world reference noise",
                          5,
                          GpuFormat.RGBA8_UNORM,
                          tileSize,
                          tileSize,
                          1,
                          1);
                  try {
                    device.createCommandEncoder().writeToTexture(noiseTexture, noiseImage);
                    noiseView = device.createTextureView(noiseTexture);
                  } catch (Throwable failure) {
                    noiseTexture.close();
                    throw failure;
                  }
                }
                AtomicBoolean noiseClosed = new AtomicBoolean();
                Runnable closeNoise =
                    () -> {
                      if (noiseClosed.compareAndSet(false, true)) {
                        try {
                          noiseView.close();
                        } finally {
                          noiseTexture.close();
                        }
                      }
                    };
                try {
                  var main = client.gameRenderer.mainRenderTarget();
                  int width = main.width;
                  int height = main.height;
                  Path directory =
                      Path.of(
                          Objects.requireNonNull(System.getProperty("cbbg.test.evidence")),
                          "world-pixels",
                          demo ? "demo" : "enabled");
                  Files.createDirectories(directory);
                  Files.copy(
                      noisePath,
                      directory.resolve("noise.png"),
                      java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                  var buffer =
                      RenderSystem.getDevice()
                          .createBuffer(
                              () -> "CBBG world float readback", 9, (long) width * height * 16);
                  try {
                    RenderSystem.getDevice()
                        .createCommandEncoder()
                        .copyTextureToBuffer(
                            Objects.requireNonNull(main.getColorTexture()),
                            buffer,
                            0,
                            () -> {
                              try (var mapped = buffer.map(true, false)) {
                                var bytes = mapped.data().order(ByteOrder.nativeOrder());
                                float[] values = new float[width * height * 4];
                                bytes.asFloatBuffer().get(values);
                                byte[] retained = new byte[values.length * 4];
                                bytes.get(retained);
                                Files.write(directory.resolve("source-rgba32f.bin"), retained);
                                source.complete(values);
                              } catch (Throwable failure) {
                                source.completeExceptionally(failure);
                              } finally {
                                buffer.close();
                              }
                            },
                            0);
                  } catch (Throwable failure) {
                    buffer.close();
                    throw failure;
                  }
                  try (var _ = ProcessedDitherInputs.overrideNoise(noiseView)) {
                    Screenshot.takeScreenshot(
                        main,
                        image -> {
                          try (image) {
                            if (image.getWidth() != width || image.getHeight() != height) {
                              throw new AssertionError("World screenshot dimensions changed");
                            }
                            image.writeToFile(directory.resolve("actual.png"));
                            int[] pixels = image.getPixels();
                            closeNoise.run();
                            actual.complete(pixels);
                          } catch (Throwable failure) {
                            try {
                              closeNoise.run();
                            } catch (Throwable closeFailure) {
                              failure.addSuppressed(closeFailure);
                            }
                            actual.completeExceptionally(failure);
                          }
                        });
                  }
                  return source.<int[], @Nullable Void>thenCombine(
                      actual,
                      (floats, pixels) -> {
                        compare(directory, width, height, tileSize, noise, floats, pixels, demo);
                        return null;
                      });
                } catch (Throwable failure) {
                  closeNoise.run();
                  throw failure;
                }
              } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
              }
            });
    context.waitFor(client -> result.isDone(), 200);
    result.join();
  }

  private static void compare(
      Path directory,
      int width,
      int height,
      int tileSize,
      int[] noise,
      float[] source,
      int[] actual,
      boolean demo) {
    int changed = 0;
    int boundaries = 0;
    int mismatches = 0;
    int goldenMismatches = 0;
    String first = null;
    String renderer = FabricLoader.getInstance().isModLoaded("sodium") ? "sodium/" : "";
    String goldenPath =
        "/cbbg-world-goldens/26.3/" + renderer + (demo ? "demo" : "enabled") + ".png";
    var goldenStream = ReleaseWorldPixelsGameTest.class.getResourceAsStream(goldenPath);
    if (goldenStream == null) throw new AssertionError("Missing world golden: " + goldenPath);
    try (goldenStream;
        NativeImage golden = NativeImage.read(goldenStream);
        NativeImage expectedImage = new NativeImage(width, height, false)) {
      if (golden.getWidth() != width || golden.getHeight() != height) {
        throw new AssertionError("World viewport differs from the golden");
      }
      for (int y = 0; y < height; y++) {
        int gpuY = height - 1 - y;
        for (int x = 0; x < width; x++) {
          int noisePixel =
              noise[
                  DitherReference.noiseCoordinate(gpuY, 1, tileSize) * tileSize
                      + DitherReference.noiseCoordinate(x, 1, tileSize)];
          int expected = 0xff000000;
          for (int channel = 0; channel < 3; channel++) {
            int shift = 16 - channel * 8;
            int noiseByte = noisePixel >>> shift & 255;
            float input = source[(gpuY * width + x) * 4 + channel];
            if (!Float.isFinite(input)) throw new AssertionError("Nonfinite world source");
            int value = DitherReference.channel(input, noiseByte, 2, x, width, demo);
            expected |= value << shift;
            int plain = DitherReference.channel(input, noiseByte, 0, x, width, demo);
            if (value != plain) changed++;
            // Only shader float arithmetic within 0.0001 of an integer rounding
            // threshold may differ by one LSB; all other channels must be exact.
            double rounded =
                Math.clamp(input, 0, 1) * 255.0
                    + (noiseByte / 255.0 - 0.5) * (demo && x < width / 2 ? 0 : 2)
                    + 0.5;
            boolean boundary = Math.abs(rounded - Math.rint(rounded)) <= 0.0001;
            if (boundary) boundaries++;
            int observed = actual[y * width + x] >>> shift & 255;
            if (Math.abs(value - observed) > (boundary ? 1 : 0)) {
              mismatches++;
              if (first == null)
                first = x + "," + y + ":" + channel + " expected=" + value + " actual=" + observed;
            }
          }
          if ((actual[y * width + x] >>> 24) != 255) {
            throw new AssertionError("World screenshot alpha was not opaque");
          }
          expectedImage.setPixel(x, y, expected);
          if (golden.getPixel(x, y) != expected) goldenMismatches++;
        }
      }
      expectedImage.writeToFile(directory.resolve("expected.png"));
      Files.writeString(
          directory.resolve("comparison.json"),
          "{\"width\":"
              + width
              + ",\"height\":"
              + height
              + ",\"noiseFrame\":0,\"strength\":2"
              + ",\"sourceByteOrder\":\""
              + ByteOrder.nativeOrder()
              + "\""
              + ",\"changedChannels\":"
              + changed
              + ",\"boundaryChannels\":"
              + boundaries
              + ",\"mismatchedChannels\":"
              + mismatches
              + ",\"goldenMismatchedPixels\":"
              + goldenMismatches
              + ",\"goldenResource\":\""
              + goldenPath
              + "\",\"approvedGolden\":true}\n");
      if (changed == 0) throw new AssertionError("World fixture cannot detect a missing effect");
      if (mismatches != 0)
        throw new AssertionError(
            "World CPU pixel mismatch: " + first + " (" + mismatches + " channels)");
      if (goldenMismatches != 0)
        throw new AssertionError(
            "World scene differs from golden: " + goldenMismatches + " pixels");
    } catch (java.io.IOException failure) {
      throw new AssertionError("Could not retain world pixel evidence", failure);
    }
  }
}
