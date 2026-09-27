package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.qb20nh.cbbg.reference.DitherReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import org.joml.Vector4f;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Checks packaged screenshot dithering with deterministic pass inputs. */
@NullMarked
public final class ReleaseDitherGameTest implements FabricClientGameTest {
  private static final int WIDTH = 6;
  private static final int HEIGHT = 4;
  private static final int WAIT_TICKS = 600;

  @Override
  public void runTest(ClientGameTestContext context) {
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      String previousMode = ReleaseClient.settings().get("mode").getAsString();
      Fixture fixture = context.computeOnClient(client -> new Fixture());
      try {
        mode(context, "enabled");
        for (float strength : new float[] {0, 0.5f, 1, 2, 4}) {
          for (float scale : new float[] {1, 0.5f}) {
            check(context, fixture, false, strength, scale);
          }
        }
        mode(context, "demo");
        for (float strength : new float[] {0, 0.5f, 1, 2, 4}) {
          for (float scale : new float[] {1, 0.5f}) {
            check(context, fixture, true, strength, scale);
          }
        }
        checkResize(context, fixture);
        context.waitFor(
            client -> {
              var output = ProcessedRenderObservations.lastDitherOutput();
              var main = client.gameRenderer.mainRenderTarget();
              return output != null
                  && !output.texture().isClosed()
                  && output.getWidth(0) == main.width
                  && output.getHeight(0) == main.height;
            },
            WAIT_TICKS);
        GpuTextureView active =
            context.computeOnClient(
                client -> {
                  var view = ProcessedRenderObservations.lastDitherOutput();
                  if (view == null || view.texture().isClosed()) {
                    throw new AssertionError("Expected a live dither output before disabling");
                  }
                  Objects.requireNonNull(client.getConnection())
                      .sendCommand("cbbg mode set disabled");
                  return view;
                });
        context.waitFor(client -> active.texture().isClosed(), WAIT_TICKS);
        ReleaseClient.assertNoDraws(context);

        CompletableFuture<?> reload =
            context.computeOnClient(client -> client.reloadResourcePacks());
        context.waitFor(client -> reload.isDone(), WAIT_TICKS);
        reload.join();
        context.waitFor(client -> client.gui.overlay() == null, WAIT_TICKS);
        mode(context, "enabled");
        check(context, fixture, false, 1, 1);
        mode(context, "demo");
        check(context, fixture, true, 1, 1);
      } finally {
        try {
          ReleaseClient.command(
              context, "mode set " + previousMode.toLowerCase(java.util.Locale.ROOT));
          context.waitFor(
              client -> previousMode.equals(ReleaseClient.settings().get("mode").getAsString()),
              WAIT_TICKS);
        } finally {
          context.runOnClient(client -> fixture.close());
        }
      }
    }
  }

  private static void mode(ClientGameTestContext context, String mode) {
    ReleaseClient.command(context, "mode set " + mode);
    context.waitFor(
        client -> mode.equalsIgnoreCase(ReleaseClient.settings().get("mode").getAsString()),
        WAIT_TICKS);
    long before = ProcessedRenderObservations.draws();
    ReleaseClient.awaitDrawAfter(context, before);
  }

  private static void check(
      ClientGameTestContext context, Fixture fixture, boolean demo, float strength, float scale) {
    CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
    context.runOnClient(
        client ->
            capture(
                client.gameRenderer.mainRenderTarget(),
                fixture.source,
                fixture,
                fixture.info(strength, scale),
                new Expected(demo, strength, scale),
                result));
    await(context, result);
  }

  private static void checkResize(ClientGameTestContext context, Fixture fixture) {
    CompletableFuture<@Nullable Void> first = new CompletableFuture<>();
    CompletableFuture<@Nullable Void> second = new CompletableFuture<>();
    context.runOnClient(
        client -> {
          RenderTarget main = client.gameRenderer.mainRenderTarget();
          GpuTextureView old =
              capture(
                  main,
                  fixture.source,
                  fixture,
                  fixture.info(1, 1),
                  new Expected(true, 1, 1),
                  first);
          if (old.texture().isClosed()) {
            throw new AssertionError("Dither output closed before resize");
          }
          GpuTextureView resized =
              capture(main, fixture.small, fixture, fixture.info(1, 1), null, second);
          if (!old.texture().isClosed()
              || resized.texture().isClosed()
              || resized.getWidth(0) != 3
              || resized.getHeight(0) != 2) {
            throw new AssertionError("Dither resize did not replace the old output");
          }
        });
    await(context, first);
    await(context, second);
  }

  private static GpuTextureView capture(
      RenderTarget main,
      TextureTarget source,
      Fixture fixture,
      GpuBuffer info,
      @Nullable Expected expected,
      CompletableFuture<@Nullable Void> result) {
    int width = main.width;
    int height = main.height;
    long selections = ProcessedRenderObservations.ditherSelections();
    long draws = ProcessedRenderObservations.draws();
    try {
      main.resize(source.width, source.height);
      // The packaged ScreenshotMixin and DitherController still choose the pass,
      // pipeline, output target, and readback. Only three bound inputs change.
      try (var ignored =
          ProcessedDitherInputs.overrideAll(
              Objects.requireNonNull(source.getColorTextureView()),
              fixture.noiseView,
              info.slice())) {
        Screenshot.takeScreenshot(
            main,
            1,
            image -> {
              try (image) {
                assertPixels(image, source.width, source.height, expected);
                if (expected != null) {
                  Path path =
                      Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")))
                          .resolve(
                              "dither-"
                                  + System.getProperty("cbbg.test.backend")
                                  + "-"
                                  + expected.demo
                                  + "-"
                                  + expected.strength
                                  + "-"
                                  + expected.scale
                                  + ".png");
                  Files.createDirectories(Objects.requireNonNull(path.getParent()));
                  image.writeToFile(path);
                }
                result.complete(null);
              } catch (Throwable failure) {
                result.completeExceptionally(failure);
              }
            });
      }
      GpuTextureView output = ProcessedRenderObservations.lastDitherOutput();
      if (ProcessedRenderObservations.ditherSelections() <= selections
          || ProcessedRenderObservations.draws() <= draws
          || output == null
          || output.texture().isClosed()
          || output.texture().getFormat() != GpuFormat.RGBA8_UNORM
          || output.getWidth(0) != source.width
          || output.getHeight(0) != source.height) {
        throw new AssertionError("Screenshot did not draw into the expected RGBA8 dither output");
      }
      return output;
    } finally {
      main.resize(width, height);
    }
  }

  private static void assertPixels(
      NativeImage image, int width, int height, @Nullable Expected expected) {
    if (image.getWidth() != width || image.getHeight() != height) {
      throw new AssertionError("Unexpected dither screenshot dimensions");
    }
    if (expected == null) return;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int gpuY = height - 1 - y;
        int noiseX = DitherReference.noiseCoordinate(x, expected.scale, 2);
        int noiseY = DitherReference.noiseCoordinate(gpuY, expected.scale, 2);
        int noise = ((noiseX + noiseY) & 1) == 0 ? 0 : 255;
        int channel =
            DitherReference.channel(
                127.25 / 255, noise, expected.strength, x, width, expected.demo);
        int pixel = 0xff000000 | channel * 0x010101;
        if (image.getPixel(x, y) != pixel) {
          throw new AssertionError(
              "Dither mismatch at "
                  + x
                  + ","
                  + y
                  + " demo="
                  + expected.demo
                  + " strength="
                  + expected.strength
                  + " scale="
                  + expected.scale
                  + " expected="
                  + Integer.toHexString(pixel)
                  + " actual="
                  + Integer.toHexString(image.getPixel(x, y)));
        }
      }
    }
  }

  private static void await(
      ClientGameTestContext context, CompletableFuture<@Nullable Void> result) {
    context.waitFor(client -> result.isDone(), 200);
    result.join();
  }

  private record Expected(boolean demo, float strength, float scale) {}

  private static final class Fixture implements AutoCloseable {
    private final TextureTarget source;
    private final TextureTarget small;
    private final GpuTexture noise;
    private final GpuTextureView noiseView;
    private final List<GpuBuffer> infos = new ArrayList<>();

    private Fixture() {
      var device = RenderSystem.getDevice();
      source =
          new TextureTarget("CBBG dither fixture", WIDTH, HEIGHT, GpuFormat.RGBA32_FLOAT, null);
      small = new TextureTarget("CBBG resize fixture", 3, 2, GpuFormat.RGBA32_FLOAT, null);
      var color = new Vector4f(127.25f / 255, 127.25f / 255, 127.25f / 255, 0.375f);
      device
          .createCommandEncoder()
          .clearColorTexture(Objects.requireNonNull(source.getColorTexture()), color);
      device
          .createCommandEncoder()
          .clearColorTexture(Objects.requireNonNull(small.getColorTexture()), color);
      noise = device.createTexture("CBBG test noise", 5, GpuFormat.RGBA8_UNORM, 2, 2, 1, 1);
      try (NativeImage pixels = new NativeImage(2, 2, false)) {
        for (int y = 0; y < 2; y++) {
          for (int x = 0; x < 2; x++) {
            pixels.setPixelABGR(x, y, ((x + y) & 1) == 0 ? 0xff000000 : 0xffffffff);
          }
        }
        device.createCommandEncoder().writeToTexture(noise, pixels);
      }
      noiseView = device.createTextureView(noise);
    }

    private GpuBuffer info(float strength, float scale) {
      ByteBuffer data = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
      data.putFloat(strength).putFloat(0).putFloat(scale).putFloat(scale).flip();
      GpuBuffer buffer =
          RenderSystem.getDevice()
              .createBuffer(() -> "CBBG test dither info", GpuBuffer.USAGE_UNIFORM, data);
      infos.add(buffer);
      return buffer;
    }

    @Override
    public void close() {
      for (GpuBuffer info : infos) info.close();
      noiseView.close();
      noise.close();
      small.destroyBuffers();
      source.destroyBuffers();
    }
  }
}
