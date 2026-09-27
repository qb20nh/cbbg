package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.qb20nh.cbbg.reference.DitherReference;
import com.qb20nh.cbbg.render.DitherPass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import org.joml.Vector4f;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class DitherPassGameTest implements FabricClientGameTest {
  private static final int WIDTH = 6;
  private static final int HEIGHT = 4;

  @Override
  public void runTest(ClientGameTestContext context) {
    Fixture fixture =
        context.computeOnClient(
            client -> {
              var device = RenderSystem.getDevice();
              var source =
                  new TextureTarget(
                      "CBBG dither fixture", WIDTH, HEIGHT, GpuFormat.RGBA32_FLOAT, null);
              device
                  .createCommandEncoder()
                  .clearColorTexture(
                      Objects.requireNonNull(source.getColorTexture()),
                      new Vector4f(127.25f / 255, 127.25f / 255, 127.25f / 255, 0.375f));
              var noise =
                  device.createTexture("CBBG test noise", 5, GpuFormat.RGBA8_UNORM, 2, 2, 1, 1);
              try (NativeImage pixels = new NativeImage(2, 2, false)) {
                for (int y = 0; y < 2; y++) {
                  for (int x = 0; x < 2; x++) {
                    pixels.setPixelABGR(x, y, ((x + y) & 1) == 0 ? 0xff000000 : 0xffffffff);
                  }
                }
                device.createCommandEncoder().writeToTexture(noise, pixels);
              }
              return new Fixture(source, noise, device.createTextureView(noise), new DitherPass());
            });
    try {
      for (boolean demo : new boolean[] {false, true}) {
        for (float strength : new float[] {0, 0.5f, 1, 2, 4}) {
          for (float scale : new float[] {1, 0.5f}) {
            check(context, fixture, demo, strength, scale);
          }
        }
      }
      context.runOnClient(
          client -> {
            var old =
                fixture
                    .pass
                    .render(
                        Objects.requireNonNull(fixture.source.getColorTextureView()),
                        fixture.view,
                        1,
                        1,
                        1,
                        false)
                    .getColorTexture();
            var small =
                new TextureTarget("CBBG resize fixture", 3, 2, GpuFormat.RGBA32_FLOAT, null);
            try {
              var resized =
                  fixture.pass.render(
                      Objects.requireNonNull(small.getColorTextureView()),
                      fixture.view,
                      1,
                      1,
                      1,
                      false);
              if (!Objects.requireNonNull(old).isClosed()
                  || resized.width != 3
                  || resized.height != 2) {
                throw new AssertionError("Dither resize did not replace the old output");
              }
              var texture = resized.getColorTexture();
              fixture.pass.close();
              if (!Objects.requireNonNull(texture).isClosed()) {
                throw new AssertionError("Dither close retained its output texture");
              }
            } finally {
              small.destroyBuffers();
            }
          });
      var reload = context.computeOnClient(client -> client.reloadResourcePacks());
      context.waitFor(client -> reload.isDone(), 600);
      reload.join();
      check(context, fixture, false, 1, 1);
      check(context, fixture, true, 1, 1);
    } finally {
      context.runOnClient(client -> fixture.close());
    }
  }

  private static void check(
      ClientGameTestContext context, Fixture fixture, boolean demo, float strength, float scale) {
    CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
    context.runOnClient(
        client -> {
          var output =
              fixture.pass.render(
                  Objects.requireNonNull(fixture.source.getColorTextureView()),
                  fixture.view,
                  strength,
                  scale,
                  scale,
                  demo);
          if (Objects.requireNonNull(output.getColorTexture()).getFormat()
              != GpuFormat.RGBA8_UNORM) {
            throw new AssertionError("Dither output must be RGBA8");
          }
          Screenshot.takeScreenshot(
              output,
              image -> {
                try (image) {
                  for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                      int gpuY = HEIGHT - 1 - y;
                      int noiseX = DitherReference.noiseCoordinate(x, scale, 2);
                      int noiseY = DitherReference.noiseCoordinate(gpuY, scale, 2);
                      int noise = ((noiseX + noiseY) & 1) == 0 ? 0 : 255;
                      int channel =
                          DitherReference.channel(127.25 / 255, noise, strength, x, WIDTH, demo);
                      int expected = 0xff000000 | channel * 0x010101;
                      if (image.getPixel(x, y) != expected) {
                        throw new AssertionError(
                            "Dither mismatch at "
                                + x
                                + ","
                                + y
                                + " demo="
                                + demo
                                + " strength="
                                + strength
                                + " scale="
                                + scale
                                + " expected="
                                + Integer.toHexString(expected)
                                + " actual="
                                + Integer.toHexString(image.getPixel(x, y)));
                      }
                    }
                  }
                  Path path =
                      Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")))
                          .resolve(
                              "dither-"
                                  + System.getProperty("cbbg.test.backend")
                                  + "-"
                                  + demo
                                  + "-"
                                  + strength
                                  + "-"
                                  + scale
                                  + ".png");
                  Files.createDirectories(Objects.requireNonNull(path.getParent()));
                  image.writeToFile(path);
                  result.complete(null);
                } catch (Throwable failure) {
                  result.completeExceptionally(failure);
                }
              });
        });
    context.waitFor(client -> result.isDone(), 200);
    result.join();
  }

  private record Fixture(
      TextureTarget source, GpuTexture noise, GpuTextureView view, DitherPass pass)
      implements AutoCloseable {
    @Override
    public void close() {
      pass.close();
      view.close();
      noise.close();
      source.destroyBuffers();
    }
  }
}
