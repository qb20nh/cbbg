package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.qb20nh.cbbg.api.DitherOptions;
import com.qb20nh.cbbg.api.Dithering;
import com.qb20nh.cbbg.api.NoiseVolume;
import com.qb20nh.cbbg.render.DitherPass;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Uses only public API names from the optimized mod, with independently owned textures. */
@NullMarked
public final class ReleaseUtilitiesGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    UtilitiesBackend.checkBackend(context);
    NoiseVolume volume = NoiseVolume.generate(4, 4, 2, 42);
    var source = context.computeOnClient(client -> UtilitiesBackend.input(6, 4));
    var noise = context.computeOnClient(client -> UtilitiesBackend.noise(volume, 1));
    var pass = context.computeOnClient(client -> new DitherPass());
    try {
      for (boolean demo : new boolean[] {false, true}) {
        for (float strength : new float[] {0, 1, 2, 4}) {
          for (float scale : new float[] {1, 0.5f, 0.7f}) {
            DitherOptions options = new DitherOptions(strength, scale, 1, demo);
            TextureTarget output =
                context.computeOnClient(
                    client ->
                        pass.render(
                            Objects.requireNonNull(source.getColorTextureView()),
                            noise.view(),
                            options));
            compare(context, output, volume, options);
          }
        }
      }
      context.runOnClient(
          client -> {
            source.resize(3, 2);
            UtilitiesBackend.clear(source);
          });
      DitherOptions options = new DitherOptions(2, 1, 1, false);
      TextureTarget output =
          context.computeOnClient(
              client ->
                  pass.render(
                      Objects.requireNonNull(source.getColorTextureView()), noise.view(), options));
      compare(context, output, volume, options);
      context.runOnClient(
          client -> {
            var owned = Objects.requireNonNull(output.getColorTexture());
            pass.close();
            if (!owned.isClosed()) {
              throw new AssertionError("Utility pass retained its output");
            }
            if (noise.texture().isClosed()
                || noise.view().isClosed()
                || Objects.requireNonNull(source.getColorTexture()).isClosed()) {
              throw new AssertionError("Utility pass closed externally owned input textures");
            }
          });
    } finally {
      context.runOnClient(
          client -> {
            pass.close();
            noise.close();
            source.destroyBuffers();
          });
    }
  }

  private static void compare(
      ClientGameTestContext context,
      TextureTarget output,
      NoiseVolume noise,
      DitherOptions options) {
    int width = output.width;
    int height = output.height;
    float[] source = new float[width * height * 4];
    Arrays.fill(source, 127f / 255);
    for (int i = 3; i < source.length; i += 4) source[i] = 1;
    byte[] expected = Dithering.rgba8(source, width, height, noise, 1, options);
    var completion = new CompletableFuture<@Nullable Void>();
    context.runOnClient(
        client ->
            Screenshot.takeScreenshot(
                output,
                image -> {
                  try (image) {
                    for (int y = 0; y < height; y++) {
                      for (int x = 0; x < width; x++) {
                        int offset = ((height - 1 - y) * width + x) * 4;
                        int pixel = image.getPixel(x, y);
                        for (int channel = 0; channel < 4; channel++) {
                          int shift = channel == 3 ? 24 : (2 - channel) * 8;
                          int actual = pixel >>> shift & 255;
                          if (actual != (expected[offset + channel] & 255)) {
                            throw new AssertionError(
                                "CPU/GPU utility mismatch at "
                                    + x
                                    + ","
                                    + y
                                    + " channel "
                                    + channel
                                    + ": "
                                    + actual
                                    + " versus "
                                    + (expected[offset + channel] & 255));
                          }
                        }
                      }
                    }
                    completion.complete(null);
                  } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                  }
                }));
    context.waitFor(client -> completion.isDone(), 200);
    completion.join();
  }
}
