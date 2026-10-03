package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;

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
    JsonObject original = settings();
    boolean originalHud = context.computeOnClient(ReleaseWorldTarget::hudHidden);
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
      server.runCommand("tp @a 0.5 -58 0.5 0 0");
      ReleaseViewport.waitForChunks(world);
      CommandDispatcher<FabricClientCommandSource> dispatcher =
          Objects.requireNonNull(
              context.computeOnClient(client -> ClientCommands.getActiveDispatcher()));
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
      try {
        context.waitFor(client -> Files.isRegularFile(noisePath()), WAIT_TICKS);
        context.waitTicks(20);
        Capture disabled = capture(context, "disabled", false);
        checkDisabled(disabled);
        command(context, dispatcher, source, "mode set enabled");
        context.waitFor(client -> ReleaseWorldTarget.noise() != null, WAIT_TICKS);
        context.waitTicks(20);
        Capture enabled = capture(context, "enabled", true);
        int enabledChanged = checkDither(enabled, false);
        command(context, dispatcher, source, "mode set demo");
        context.waitTicks(10);
        Capture demo = capture(context, "demo", true);
        int demoChanged = checkDither(demo, true);
        if (disabled.width() != enabled.width()
            || enabled.width() != demo.width()
            || disabled.height() != enabled.height()
            || enabled.height() != demo.height()) {
          throw new AssertionError("World screenshot dimensions changed across modes");
        }
        String backend = context.computeOnClient(client -> ReleaseBackend.identity()[0]);
        writeReceipt(disabled, enabled, demo, enabledChanged, demoChanged, backend);
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
            });
      }
    }
  }

  private static Capture capture(ClientGameTestContext context, String mode, boolean floating) {
    Capture capture =
        context.computeOnClient(
            client -> {
              RenderTarget main = ReleaseWorldTarget.main(client);
              int width = main.width;
              int height = main.height;
              if (width <= 0 || height <= 0) throw new AssertionError("Empty world viewport");
              GpuTexture color = Objects.requireNonNull(main.getColorTexture());
              String format = floating ? "rgba32f" : "rgba8";
              if (!format.equals(ReleaseAllocationFormat.actual(color))) {
                throw new AssertionError("World main target is not " + format);
              }
              Path imagePath =
                  mode.equals("disabled")
                      ? evidence().resolve("world-disabled/actual.png")
                      : evidence().resolve("world-pixels").resolve(mode).resolve("actual.png");
              CompletableFuture<byte[]> source =
                  read(color, width * height * (floating ? 16 : 4), floating);
              CompletableFuture<int[]> screenshot = new CompletableFuture<>();
              Screenshot.takeScreenshot(
                  main,
                  image -> {
                    try (image) {
                      if (image.getWidth() != width || image.getHeight() != height) {
                        throw new AssertionError("World screenshot dimensions changed");
                      }
                      Files.createDirectories(Objects.requireNonNull(imagePath.getParent()));
                      image.writeToFile(imagePath);
                      screenshot.complete(image.getPixels());
                    } catch (Throwable failure) {
                      screenshot.completeExceptionally(failure);
                    }
                  });
              GpuTexture noise = floating ? ReleaseWorldTarget.noise() : null;
              if (floating && noise == null) {
                throw new AssertionError("World screenshot had no presented noise texture");
              }
              CompletableFuture<byte[]> noiseBytes =
                  noise == null
                      ? CompletableFuture.completedFuture(new byte[0])
                      : read(noise, noise.getWidth(0) * noise.getHeight(0) * 4, false);
              return new Capture(width, height, source, noiseBytes, screenshot, imagePath);
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

  static CompletableFuture<byte[]> read(GpuTexture texture, int length, boolean floating) {
    if (texture instanceof GlTexture gl) {
      int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
      int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
      int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
      int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
      try {
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, gl.glId());
        ByteBuffer bytes = ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
        GL11.glGetTexImage(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA,
            floating ? GL11.GL_FLOAT : GL11.GL_UNSIGNED_BYTE,
            bytes);
        byte[] result = new byte[length];
        bytes.get(0, result);
        return CompletableFuture.completedFuture(result);
      } finally {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
      }
    }
    GpuBuffer buffer =
        RenderSystem.getDevice()
            .createBuffer(
                () -> "CBBG world readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                length);
    CompletableFuture<byte[]> result = new CompletableFuture<>();
    try {
      RenderSystem.getDevice()
          .createCommandEncoder()
          .copyTextureToBuffer(
              texture,
              buffer,
              0,
              () -> {
                try (buffer) {
                  result.complete(ReleaseWorldTarget.mapped(buffer, length));
                } catch (Throwable failure) {
                  result.completeExceptionally(failure);
                }
              },
              0);
    } catch (RuntimeException | Error failure) {
      buffer.close();
      result.completeExceptionally(failure);
    }
    return result;
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

  private static int checkDither(Capture capture, boolean demo) {
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
          int noiseBase =
              (DitherReference.noiseCoordinate(gpuY, 1, SIZE) * SIZE
                      + DitherReference.noiseCoordinate(x, 1, SIZE))
                  * 4;
          for (int channel = 0; channel < 3; channel++) {
            float input = floats.getFloat(((gpuY * width + x) * 4 + channel) * 4);
            if (!Float.isFinite(input)) throw new AssertionError("Nonfinite world color source");
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
          expectedImage.setPixel(x, y, expectedPixel);
        }
      }
      Path expectedPath =
          Objects.requireNonNull(capture.image().getParent()).resolve("expected.png");
      expectedImage.writeToFile(expectedPath);
    } catch (java.io.IOException failure) {
      throw new AssertionError("Cannot save CPU world reference", failure);
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
      String backend) {
    try {
      byte[] controlImage = Files.readAllBytes(disabled.image());
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(controlImage);
      JsonObject receipt = new JsonObject();
      receipt.addProperty("minecraftVersion", version());
      receipt.addProperty("backend", backend);
      receipt.addProperty("scene", "spectator-concrete-glowstone-v1");
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
      Files.writeString(evidence().resolve("world-comparison.json"), receipt.toString());
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
      CompletableFuture<byte[]> source,
      CompletableFuture<byte[]> noise,
      CompletableFuture<int[]> screenshot,
      Path image) {}
}
