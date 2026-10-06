package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.CommandDispatcher;
import com.qb20nh.cbbg.reference.DitherReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;

/** Compares actual world screenshots to main-target readback and the shared CPU dither oracle. */
@NullMarked
public final class ReleaseWorldPixelsGameTest implements FabricClientGameTest {
  private static final int SIZE = 16;
  private static final int DEPTH = 8;
  private static final long SEED = 0;
  private static final int WAIT_TICKS = 600;

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    runScene(context, false);
  }

  static void runScene(ClientGameTestContext context, boolean translucent) {
    runScene(context, translucent, 1, false);
  }

  static void runScaledScene(ClientGameTestContext context, float scale) {
    runScene(context, false, scale, true);
  }

  private static void runScene(
      ClientGameTestContext context, boolean translucent, float scale, boolean scaled) {
    JsonObject original = context.computeOnClient(client -> currentSettings());
    boolean originalHud = context.computeOnClient(ReleaseWorldTarget::hudHidden);
    Object transparency = context.computeOnClient(ReleaseTransparencySettings::snapshot);
    String scenario =
        translucent
            ? "world-transparency-" + context.computeOnClient(ReleaseTransparencySettings::enabled)
            : scaled ? "world-renderscale-" + scale : "world-pixels";
    FabricClientCommandSource source = ReleaseGenerationGameTest.silentSource();
    try (var world = context.worldBuilder().create()) {
      var server = world.getServer();
      server.runCommand("gamemode spectator @a");
      server.runCommand("time set 6000");
      server.runCommand("weather clear");
      server.runCommand("fill -8 -64 5 8 -50 5 minecraft:white_concrete");
      server.runCommand("fill -8 -64 5 -3 -55 5 minecraft:red_concrete");
      server.runCommand("fill 2 -57 5 8 -50 5 minecraft:blue_concrete");
      server.runCommand("setblock -1 -56 5 minecraft:glowstone");
      if (translucent) {
        server.runCommand("fill -8 -64 3 8 -50 3 minecraft:blue_stained_glass");
      }
      server.runCommand("tp @a 0.5 -58 0.5 0 0");
      ReleaseViewport.waitForChunks(world);
      CommandDispatcher<FabricClientCommandSource> dispatcher =
          Objects.requireNonNull(
              context.computeOnClient(client -> ReleaseCommands.getActiveDispatcher()));
      try {
        context.runOnClient(
            client -> {
              ReleaseWorldTarget.hideHud(client, true);
              command(dispatcher, source, "mode set disabled");
              command(dispatcher, source, "stbn size " + SIZE);
              command(dispatcher, source, "stbn depth " + DEPTH);
              command(dispatcher, source, "stbn seed " + SEED);
              command(dispatcher, source, "format set rgba32f");
              command(dispatcher, source, "stbn generate");
            });
        context.waitFor(client -> Files.isRegularFile(noisePath()), WAIT_TICKS);
        context.waitTicks(20);
        Capture disabled = capture(context, "disabled", false, scenario, scale, scaled);
        checkDisabled(disabled);
        command(context, dispatcher, source, "mode set enabled");
        context.waitFor(client -> ReleaseWorldCapture.noiseReady(), WAIT_TICKS);
        context.waitTicks(20);
        Capture enabled = capture(context, "enabled", true, scenario, scale, scaled);
        int enabledChanged = checkDither(enabled, false, translucent);
        command(context, dispatcher, source, "mode set demo");
        context.waitTicks(10);
        Capture demo = capture(context, "demo", true, scenario, scale, scaled);
        int demoChanged = checkDither(demo, true, translucent);
        if (disabled.width() != enabled.width()
            || enabled.width() != demo.width()
            || disabled.height() != enabled.height()
            || enabled.height() != demo.height()) {
          throw new AssertionError("World screenshot dimensions changed across modes");
        }
        String backend = context.computeOnClient(client -> ReleaseBackend.identity()[0]);
        writeReceipt(disabled, enabled, demo, enabledChanged, demoChanged, backend, scenario);
      } finally {
        context.runOnClient(
            client -> {
              command(dispatcher, source, "mode set disabled");
              command(dispatcher, source, "stbn size " + original.get("stbnSize").getAsInt());
              command(dispatcher, source, "stbn depth " + original.get("stbnDepth").getAsInt());
              command(dispatcher, source, "stbn seed " + original.get("stbnSeed").getAsLong());
              command(
                  dispatcher,
                  source,
                  "format set "
                      + original.get("pixelFormat").getAsString().toLowerCase(Locale.ROOT));
              command(
                  dispatcher,
                  source,
                  "mode set " + original.get("mode").getAsString().toLowerCase(Locale.ROOT));
              ReleaseWorldTarget.hideHud(client, originalHud);
              ReleaseTransparencySettings.restore(client, transparency);
            });
      }
    }
  }

  private static Capture capture(
      ClientGameTestContext context,
      String mode,
      boolean floating,
      String scenario,
      float scale,
      boolean scaled) {
    Capture capture =
        context.computeOnClient(
            client -> {
              RenderTarget main = ReleaseWorldTarget.main(client);
              int width = main.width;
              int height = main.height;
              if (width <= 0 || height <= 0) throw new AssertionError("Empty world viewport");
              String format = floating ? "rgba32f" : "rgba8";
              if (!format.equals(ReleaseWorldCapture.format(main))) {
                throw new AssertionError("World main target is not " + format);
              }
              int scaledWidth = width;
              int scaledHeight = height;
              if (scaled) {
                RenderTarget target =
                    (RenderTarget)
                        Objects.requireNonNull(
                            RenderScaleTestAccess.field(
                                Objects.requireNonNull(
                                    RenderScaleTestAccess.call(null, "getInstance")),
                                "renderTarget"));
                scaledWidth = target.width;
                scaledHeight = target.height;
                String scaledFormat = ReleaseWorldCapture.format(target);
                if (scaledWidth != ReleaseWorldCapture.scaledDimension(width, scale)
                    || scaledHeight != ReleaseWorldCapture.scaledDimension(height, scale)
                    || (floating && !format.equals(scaledFormat))) {
                  throw new AssertionError(
                      "RenderScale dimensions or precision differ at "
                          + scale
                          + ": main="
                          + width
                          + "x"
                          + height
                          + " scaled="
                          + scaledWidth
                          + "x"
                          + scaledHeight
                          + " mode="
                          + mode
                          + " expectedFormat="
                          + format
                          + " actualFormat="
                          + scaledFormat);
                }
              }
              Path imagePath =
                  mode.equals("disabled") && scenario.equals("world-pixels")
                      ? evidence().resolve("world-disabled/actual.png")
                      : evidence().resolve(scenario).resolve(mode).resolve("actual.png");
              CompletableFuture<byte[]> source = ReleaseWorldCapture.source(main, floating);
              CompletableFuture<int[]> screenshot = new CompletableFuture<>();
              ReleaseWorldCapture.screenshot(
                  main,
                  image -> {
                    try (image) {
                      if (image.getWidth() != width || image.getHeight() != height) {
                        throw new AssertionError("World screenshot dimensions changed");
                      }
                      Files.createDirectories(Objects.requireNonNull(imagePath.getParent()));
                      image.writeToFile(imagePath);
                      screenshot.complete(ReleaseImagePixels.pixels(image));
                    } catch (Throwable failure) {
                      screenshot.completeExceptionally(failure);
                    }
                  });
              CompletableFuture<byte[]> noisePixels = ReleaseWorldCapture.noise(floating);
              JsonObject transparency = ReleaseTransparencySettings.evidence(client, floating);
              return new Capture(
                  width,
                  height,
                  scaledWidth,
                  scaledHeight,
                  source,
                  noisePixels,
                  screenshot,
                  imagePath,
                  transparency);
            });
    context.waitFor(
        client ->
            capture.source().isDone() && capture.noise().isDone() && capture.screenshot().isDone(),
        200);
    capture.source().join();
    capture.noise().join();
    capture.screenshot().join();
    return capture;
  }

  private static void checkDisabled(Capture capture) {
    byte[] source = capture.source().join();
    int[] screenshot = capture.screenshot().join();
    int width = capture.width();
    int height = capture.height();
    int differingPixels = 0;
    int first = screenshot[0];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int offset = ((height - 1 - y) * width + x) * 4;
        int observed = screenshot[y * width + x];
        if (observed >>> 24 != 255)
          throw new AssertionError("World screenshot alpha is not opaque");
        if (observed != first) differingPixels++;
        for (int channel = 0; channel < 3; channel++) {
          int expected = Byte.toUnsignedInt(source[offset + channel]);
          int actual = observed >>> (16 - channel * 8) & 255;
          if (expected != actual) {
            throw new AssertionError(
                "Disabled world screenshot orientation/color differs from main target at "
                    + x
                    + ","
                    + y
                    + ":"
                    + channel);
          }
        }
      }
    }
    if (differingPixels < width * height / 20) {
      throw new AssertionError("Disabled world scene lacks visible geometry");
    }
  }

  private static int checkDither(Capture capture, boolean demo, boolean translucent) {
    byte[] raw = capture.source().join();
    byte[] noise = capture.noise().join();
    int[] screenshot = capture.screenshot().join();
    int width = capture.width();
    int height = capture.height();
    if (noise.length != SIZE * SIZE * 4) {
      throw new AssertionError("World noise dimensions differ from requested 16x16 frame");
    }
    ByteBuffer floats = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder());
    float strength = settings().get("strength").getAsFloat();
    if (!(strength > 0)) throw new AssertionError("World reference requires positive strength");
    int changed = 0;
    int mismatched = 0;
    int fractionalChannels = 0;
    float scaleX = Math.min((float) capture.scaledWidth() / width, 1);
    float scaleY = Math.min((float) capture.scaledHeight() / height, 1);
    String first = "";
    try (NativeImage expectedImage = new NativeImage(width, height, false)) {
      for (int y = 0; y < height; y++) {
        int gpuY = height - 1 - y;
        for (int x = 0; x < width; x++) {
          int observed = screenshot[y * width + x];
          if (observed >>> 24 != 255) {
            throw new AssertionError("World screenshot alpha is not opaque");
          }
          int expectedPixel = 0xff000000;
          // RenderScale truncates each internal dimension independently. Its pixels are
          // selected by the output pixel center, rather than by a rounded nominal scale.
          int noiseX = Math.floorMod((int) Math.floor((x + 0.5) * scaleX), SIZE);
          int noiseY = Math.floorMod((int) Math.floor((gpuY + 0.5) * scaleY), SIZE);
          int noiseBase = (noiseY * SIZE + noiseX) * 4;
          for (int channel = 0; channel < 3; channel++) {
            float input = floats.getFloat(((gpuY * width + x) * 4 + channel) * 4);
            if (!Float.isFinite(input)) throw new AssertionError("Nonfinite world color source");
            if (input > 0
                && input < 1
                && Math.abs(input * 255 - Math.round(input * 255)) > 0.002f) {
              fractionalChannels++;
            }
            int noiseByte = Byte.toUnsignedInt(noise[noiseBase + channel]);
            int expected = DitherReference.channel(input, noiseByte, strength, x, width, demo);
            int plain = DitherReference.channel(input, noiseByte, 0, x, width, demo);
            if (expected != plain) changed++;
            expectedPixel |= expected << (16 - channel * 8);
            double threshold =
                Math.clamp(input, 0, 1) * 255.0
                    + (noiseByte / 255.0 - 0.5) * (demo && x < width / 2 ? 0 : strength)
                    + 0.5;
            int tolerance = Math.abs(threshold - Math.rint(threshold)) <= 0.0001 ? 1 : 0;
            int actual = observed >>> (16 - channel * 8) & 255;
            if (Math.abs(expected - actual) > tolerance) {
              mismatched++;
              if (first.isEmpty()) {
                first = x + "," + y + ":" + channel + " expected=" + expected + " actual=" + actual;
              }
            }
          }
          ReleaseImagePixels.setArgb(expectedImage, x, y, expectedPixel);
        }
      }
      Path expectedPath =
          Objects.requireNonNull(capture.image().getParent()).resolve("expected.png");
      expectedImage.writeToFile(expectedPath);
      Path directory = Objects.requireNonNull(capture.image().getParent());
      Files.write(directory.resolve("source-rgba32f.bin"), raw);
      Files.write(directory.resolve("noise-rgba8.bin"), noise);
      JsonObject receipt = new JsonObject();
      receipt.addProperty("width", width);
      receipt.addProperty("height", height);
      receipt.addProperty("scaledWidth", capture.scaledWidth());
      receipt.addProperty("scaledHeight", capture.scaledHeight());
      receipt.addProperty("coordinateScaleX", scaleX);
      receipt.addProperty("coordinateScaleY", scaleY);
      receipt.addProperty("sourceByteOrder", ByteOrder.nativeOrder().toString());
      receipt.addProperty("changedChannels", changed);
      receipt.addProperty("fractionalChannels", fractionalChannels);
      receipt.addProperty("mismatchedChannels", mismatched);
      Files.writeString(directory.resolve("comparison.json"), receipt.toString());
    } catch (java.io.IOException failure) {
      throw new AssertionError("Cannot save CPU world reference", failure);
    }
    if (translucent && fractionalChannels == 0) {
      throw new AssertionError("Transparency quantized the world before dithering");
    }
    if (changed == 0) throw new AssertionError("World scene cannot detect dithering");
    if (mismatched != 0) {
      throw new AssertionError("World CPU reference mismatch: " + first + " (" + mismatched + ")");
    }
    return changed;
  }

  private static void writeReceipt(
      Capture disabled,
      Capture enabled,
      Capture demo,
      int enabledChanged,
      int demoChanged,
      String backend,
      String scenario) {
    try {
      byte[] controlImage = Files.readAllBytes(disabled.image());
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(controlImage);
      JsonObject receipt = new JsonObject();
      receipt.addProperty("minecraftVersion", version());
      receipt.add("disabledTransparency", disabled.transparency());
      receipt.add("enabledTransparency", enabled.transparency());
      receipt.add("demoTransparency", demo.transparency());
      receipt.addProperty("backend", backend);
      receipt.addProperty(
          "scene", scenario.equals("world-pixels") ? "spectator-concrete-glowstone-v1" : scenario);
      receipt.addProperty("width", disabled.width());
      receipt.addProperty("height", disabled.height());
      receipt.addProperty("disabledImageSha256", HexFormat.of().formatHex(digest));
      receipt.addProperty("noiseSize", SIZE);
      receipt.addProperty("noiseDepth", DEPTH);
      receipt.addProperty("noiseSeed", SEED);
      receipt.addProperty("strength", settings().get("strength").getAsFloat());
      receipt.addProperty("enabledChangedChannels", enabledChanged);
      receipt.addProperty("demoChangedChannels", demoChanged);
      receipt.addProperty("enabledImage", enabled.image().toString());
      receipt.addProperty("demoImage", demo.image().toString());
      Path receiptPath =
          scenario.equals("world-pixels")
              ? evidence().resolve("world-comparison.json")
              : evidence().resolve(scenario).resolve("comparison.json");
      Files.writeString(receiptPath, receipt.toString());
    } catch (java.io.IOException | NoSuchAlgorithmException failure) {
      throw new AssertionError("Cannot save world comparison receipt", failure);
    }
  }

  private static String version() {
    return FabricLoader.getInstance()
        .getModContainer("minecraft")
        .orElseThrow()
        .getMetadata()
        .getVersion()
        .getFriendlyString();
  }

  private static Path noisePath() {
    return FabricLoader.getInstance()
        .getGameDir()
        .resolve(".cbbg/stbn_" + SIZE + "x" + SIZE + "x" + DEPTH + "_0.png");
  }

  private static Path evidence() {
    return Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
  }

  private static JsonObject settings() {
    try (var reader =
        Files.newBufferedReader(FabricLoader.getInstance().getConfigDir().resolve("cbbg.json"))) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception failure) {
      throw new AssertionError("Cannot read packaged CBBG settings", failure);
    }
  }

  /** Read effective settings; config files may omit defaults. */
  private static JsonObject currentSettings() {
    String owner = "com.qb20nh.cbbg.config.CbbgConfig";
    try {
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      Object current =
          Objects.requireNonNull(
              type.getMethod(ReleaseMapping.memberName(owner, owner + " get()")).invoke(null));
      JsonObject values = new JsonObject();
      for (var entry :
          Map.of(
                  "stbnSize", "int stbnSize",
                  "stbnDepth", "int stbnDepth",
                  "stbnSeed", "long stbnSeed",
                  "mode", owner + "$Mode mode",
                  "pixelFormat", owner + "$PixelFormat pixelFormat")
              .entrySet()) {
        var field = type.getDeclaredField(ReleaseMapping.memberName(owner, entry.getValue()));
        field.setAccessible(true);
        Object value = Objects.requireNonNull(field.get(current));
        if (value instanceof Number number) values.addProperty(entry.getKey(), number);
        else values.addProperty(entry.getKey(), ((Enum<?>) value).name());
      }
      return values;
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot read packaged CBBG settings", failure);
    }
  }

  private static void command(
      ClientGameTestContext context,
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      String suffix) {
    context.runOnClient(client -> command(dispatcher, source, suffix));
  }

  private static void command(
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      String suffix) {
    ReleaseGenerationGameTest.command(dispatcher, source, suffix);
  }

  private record Capture(
      int width,
      int height,
      int scaledWidth,
      int scaledHeight,
      CompletableFuture<byte[]> source,
      CompletableFuture<byte[]> noise,
      CompletableFuture<int[]> screenshot,
      Path image,
      JsonObject transparency) {}
}
