package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.reference.DitherReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Pixel acceptance against the installed CBBG jar on the selected real GPU backend. */
@NullMarked
public final class ReleasePixelGameTest implements FabricClientGameTest {
  private static final int WIDTH = 8;
  private static final int HEIGHT = 4;
  private static final float STRENGTH = 4f;

  @Override
  public void runTest(ClientGameTestContext context) {
    context.runOnClient(
        client -> {
          String actual = RenderSystem.getDevice().getDeviceInfo().backendName();
          String expected = Objects.requireNonNull(System.getProperty("cbbg.test.backend"));
          if (!actual.equalsIgnoreCase(expected)) {
            throw new AssertionError("Pixel test requested " + expected + ", got " + actual);
          }
        });
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      context.runOnClient(client -> client.gui.setScreen(null));
      command(context, "stbn size 16");
      command(context, "stbn depth 8");
      command(context, "stbn seed 42");
      command(context, "stbn generate");
      command(context, "format set rgba32f");
      float originalStrength = context.computeOnClient(client -> strength());
      TextureTarget source =
          context.computeOnClient(
              client -> {
                setStrength(STRENGTH);
                TextureTarget target =
                    new TextureTarget(
                        "CBBG release pixels", WIDTH, HEIGHT, false, GpuFormat.RGBA32_FLOAT);
                upload(target);
                return target;
              });
      try {
        command(context, "mode set enabled");
        context.waitFor(client -> render(source, false) != null, 600);
        check(context, source, false);
        command(context, "mode set demo");
        context.waitFor(client -> render(source, true) != null, 600);
        check(context, source, true);
      } finally {
        context.runOnClient(
            client -> {
              source.destroyBuffers();
              setStrength(originalStrength);
            });
        command(context, "mode set disabled");
      }
    }
  }

  private static void command(ClientGameTestContext context, String command) {
    context.runOnClient(
        client -> Objects.requireNonNull(client.getConnection()).sendCommand("cbbg " + command));
  }

  private static float value(int x, int y, int channel) {
    if (channel == 3) return (20 + y * 40) / 255f;
    if (x < WIDTH / 2) return 127.25f / 255f;
    return 0.12f + (x - WIDTH / 2) * 0.11f + y * 0.055f + channel * 0.035f;
  }

  private static void upload(TextureTarget target) {
    ByteBuffer pixels =
        ByteBuffer.allocateDirect(WIDTH * HEIGHT * GpuFormat.RGBA32_FLOAT.blockSize())
            .order(ByteOrder.nativeOrder());
    for (int y = 0; y < HEIGHT; y++) {
      for (int x = 0; x < WIDTH; x++) {
        for (int channel = 0; channel < 4; channel++) pixels.putFloat(value(x, y, channel));
      }
    }
    pixels.flip();
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    var staging =
        encoder
            .transientMemory()
            .uploadGpu(pixels, GpuFormat.RGBA32_FLOAT.byteAlignment(), GpuBuffer.USAGE_COPY_SRC);
    encoder.copyBufferToTexture(
        staging,
        0,
        0,
        WIDTH,
        HEIGHT,
        Objects.requireNonNull(target.getColorTexture()),
        0,
        0,
        WIDTH,
        HEIGHT,
        0,
        0);
  }

  private static @Nullable TextureTarget render(TextureTarget source, boolean demo) {
    try {
      String owner = "com.qb20nh.cbbg.render.CbbgDither";
      Class<?> renderer = Class.forName(ReleaseMapping.className(owner));
      String method =
          ReleaseMapping.memberName(
              owner,
              demo
                  ? "com.mojang.blaze3d.pipeline.TextureTarget"
                      + " renderDemoTarget(com.mojang.blaze3d.textures.GpuTextureView)"
                  : "com.mojang.blaze3d.pipeline.TextureTarget"
                      + " renderDitheredTarget(com.mojang.blaze3d.textures.GpuTextureView)");
      return (TextureTarget)
          renderer
              .getMethod(method, GpuTextureView.class)
              .invoke(null, Objects.requireNonNull(source.getColorTextureView()));
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged CBBG render API changed", failure);
    }
  }

  private static GpuTextureView noise() {
    try {
      String owner = "com.qb20nh.cbbg.render.CbbgDither";
      Class<?> renderer = Class.forName(ReleaseMapping.className(owner));
      var field =
          renderer.getDeclaredField(
              ReleaseMapping.memberName(
                  owner, "com.qb20nh.cbbg.render.stbn.StbnTextureManager stbnManager"));
      field.setAccessible(true);
      Object manager = Objects.requireNonNull(field.get(null));
      return (GpuTextureView)
          Objects.requireNonNull(
              manager
                  .getClass()
                  .getMethod(
                      ReleaseMapping.memberName(
                          "com.qb20nh.cbbg.render.stbn.StbnTextureManager",
                          "com.mojang.blaze3d.textures.GpuTextureView getView()"))
                  .invoke(manager));
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged CBBG noise API changed", failure);
    }
  }

  private static float strength() {
    try {
      String owner = "com.qb20nh.cbbg.config.CbbgConfig";
      Class<?> config = Class.forName(ReleaseMapping.className(owner));
      Object current =
          Objects.requireNonNull(
              config
                  .getMethod(
                      ReleaseMapping.memberName(owner, "com.qb20nh.cbbg.config.CbbgConfig get()"))
                  .invoke(null));
      return (float)
          Objects.requireNonNull(
              current
                  .getClass()
                  .getMethod(ReleaseMapping.memberName(owner, "float strength()"))
                  .invoke(current));
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged CBBG config API changed", failure);
    }
  }

  private static void setStrength(float value) {
    try {
      String owner = "com.qb20nh.cbbg.config.CbbgConfig";
      Class.forName(ReleaseMapping.className(owner))
          .getMethod(ReleaseMapping.memberName(owner, "void setStrength(float)"), float.class)
          .invoke(null, value);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged CBBG config API changed", failure);
    }
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private static void check(ClientGameTestContext context, TextureTarget source, boolean demo) {
    CompletableFuture<@Nullable Void> result =
        context.computeOnClient(
            client -> {
              TextureTarget output = Objects.requireNonNull(render(source, demo));
              if (output.width != WIDTH || output.height != HEIGHT) {
                throw new AssertionError("CBBG pixel output has wrong dimensions");
              }
              if (Objects.requireNonNull(output.getColorTexture()).getFormat()
                  != GpuFormat.RGBA8_UNORM) {
                throw new AssertionError("CBBG pixel output is not RGBA8");
              }
              GpuTextureView noise = noise();
              if (noise.texture().getFormat() != GpuFormat.RGBA8_UNORM) {
                throw new AssertionError("CBBG noise is not RGBA8");
              }
              CompletableFuture<byte[]> actual =
                  read(Objects.requireNonNull(output.getColorTexture()));
              CompletableFuture<byte[]> noiseBytes = read(noise.texture());
              CompletableFuture<@Nullable Void> screenshot = new CompletableFuture<>();
              Screenshot.takeScreenshot(
                  output,
                  1,
                  image ->
                      CompletableFuture.allOf(actual, noiseBytes)
                          .whenComplete(
                              (ignored, readFailure) -> {
                                try (image) {
                                  if (readFailure != null) throw new AssertionError(readFailure);
                                  byte[] rgb = actual.join();
                                  byte[] pattern = noiseBytes.join();
                                  verify(rgb, pattern, noise.getWidth(0), noise.getHeight(0), demo);
                                  if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT) {
                                    throw new AssertionError(
                                        "Pixel screenshot has wrong dimensions");
                                  }
                                  for (int y = 0; y < HEIGHT; y++) {
                                    for (int x = 0; x < WIDTH; x++) {
                                      int offset = (y * WIDTH + x) * 4;
                                      int expected =
                                          0xff000000
                                              | Byte.toUnsignedInt(rgb[offset]) << 16
                                              | Byte.toUnsignedInt(rgb[offset + 1]) << 8
                                              | Byte.toUnsignedInt(rgb[offset + 2]);
                                      if (image.getPixel(x, HEIGHT - 1 - y) != expected) {
                                        throw new AssertionError(
                                            "Screenshot orientation, RGB, or opaque alpha at "
                                                + x
                                                + ","
                                                + y);
                                      }
                                    }
                                  }
                                  Path evidence =
                                      Path.of(
                                          Objects.requireNonNull(
                                              System.getProperty("cbbg.test.evidence")),
                                          demo
                                              ? "release-pixels-demo.png"
                                              : "release-pixels-enabled.png");
                                  Files.createDirectories(
                                      Objects.requireNonNull(evidence.getParent()));
                                  image.writeToFile(evidence);
                                  screenshot.complete(null);
                                } catch (Throwable failure) {
                                  screenshot.completeExceptionally(failure);
                                }
                              }));
              return screenshot;
            });
    context.waitFor(client -> result.isDone(), 200);
    result.join();
  }

  private static CompletableFuture<byte[]> read(GpuTexture texture) {
    int length = texture.getWidth(0) * texture.getHeight(0) * texture.getFormat().blockSize();
    GpuBuffer buffer =
        RenderSystem.getDevice()
            .createBuffer(
                () -> "CBBG release readback",
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
                try (buffer;
                    var mapped = buffer.map(true, false)) {
                  ByteBuffer data = mapped.data();
                  byte[] bytes = new byte[length];
                  data.get(0, bytes);
                  result.complete(bytes);
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

  private static void verify(
      byte[] actual, byte[] pattern, int noiseWidth, int noiseHeight, boolean demo) {
    int changed = 0;
    int twiceDifferent = 0;
    for (int y = 0; y < HEIGHT; y++) {
      for (int x = 0; x < WIDTH; x++) {
        int offset = (y * WIDTH + x) * 4;
        int tile =
            (DitherReference.noiseCoordinate(y, 1, noiseHeight) * noiseWidth
                    + DitherReference.noiseCoordinate(x, 1, noiseWidth))
                * 4;
        for (int channel = 0; channel < 3; channel++) {
          int noiseByte = Byte.toUnsignedInt(pattern[tile + channel]);
          int expected =
              DitherReference.channel(value(x, y, channel), noiseByte, STRENGTH, x, WIDTH, demo);
          int observed = Byte.toUnsignedInt(actual[offset + channel]);
          if (observed != expected) {
            throw new AssertionError(
                "CBBG pixel "
                    + x
                    + ","
                    + y
                    + " channel "
                    + channel
                    + " expected "
                    + expected
                    + ", got "
                    + observed
                    + ", demo="
                    + demo);
          }
          int plain = DitherReference.channel(value(x, y, channel), noiseByte, 0, x, WIDTH, demo);
          if (expected != plain && (!demo || x >= WIDTH / 2)) changed++;
          int twice = DitherReference.channel(expected / 255d, noiseByte, STRENGTH, x, WIDTH, demo);
          if (expected != twice && (!demo || x >= WIDTH / 2)) twiceDifferent++;
        }
        int alpha = Math.round(value(x, y, 3) * 255);
        if (Byte.toUnsignedInt(actual[offset + 3]) != alpha) {
          throw new AssertionError(
              "CBBG changed source alpha at "
                  + x
                  + ","
                  + y
                  + ": expected "
                  + alpha
                  + ", got "
                  + Byte.toUnsignedInt(actual[offset + 3]));
        }
      }
    }
    if (changed == 0 || twiceDifferent == 0) {
      throw new AssertionError("Fixture cannot distinguish enabled effect or a second dither pass");
    }
  }
}
