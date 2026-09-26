package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.stbn.STBNCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import org.joml.Vector4f;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class PresentationGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    CbbgConfig.Mode original = CbbgConfig.get().mode();
    try {
      long initial = context.computeOnClient(client -> DitherController.getPresentationCount());
      context.waitFor(
          client -> DitherController.isReady() && DitherController.getPresentationCount() > initial,
          600);
      CompletableFuture<@Nullable Void> captured = new CompletableFuture<>();
      CompletableFuture<int[]> actualPixels = new CompletableFuture<>();
      CompletableFuture<int[]> expectedPixels = new CompletableFuture<>();
      context.runOnClient(
          client -> {
            int frame = DitherController.getCurrentStbnFrameIndex();
            long presentations = DitherController.getPresentationCount();
            var main = client.gameRenderer.mainRenderTarget();
            RenderSystem.getDevice()
                .createCommandEncoder()
                .clearColorTexture(
                    Objects.requireNonNull(main.getColorTexture()),
                    new Vector4f(127.25f / 255, 127.25f / 255, 127.25f / 255, 0.375f));
            Screenshot.takeScreenshot(
                main,
                image -> {
                  try (image) {
                    if (image.getWidth() != main.width || image.getHeight() != main.height) {
                      throw new AssertionError("Live screenshot has wrong dimensions");
                    }
                    Path evidence =
                        Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
                    Files.createDirectories(evidence);
                    image.writeToFile(
                        evidence.resolve(
                            "presentation-" + System.getProperty("cbbg.test.backend") + ".png"));
                    actualPixels.complete(image.getPixels());
                    captured.complete(null);
                  } catch (Throwable failure) {
                    captured.completeExceptionally(failure);
                  }
                });
            var expected =
                DitherController.screenshot(Objects.requireNonNull(main.getColorTextureView()));
            if (expected == null) {
              throw new AssertionError("Expected live dithering to be ready");
            }
            Screenshot.takeScreenshot(
                expected,
                image -> {
                  try (image) {
                    expectedPixels.complete(image.getPixels());
                  } catch (Throwable failure) {
                    expectedPixels.completeExceptionally(failure);
                  }
                });
            if (frame != DitherController.getCurrentStbnFrameIndex()
                || presentations != DitherController.getPresentationCount()) {
              throw new AssertionError("Screenshot advanced the temporal noise sequence");
            }
          });
      context.waitFor(client -> captured.isDone(), 200);
      captured.join();
      context.waitFor(client -> expectedPixels.isDone(), 200);
      int[] expected = expectedPixels.join();
      if (!Arrays.equals(actualPixels.join(), expected)
          || Arrays.stream(expected).allMatch(pixel -> pixel == 0xff7f7f7f)) {
        throw new AssertionError("Normal screenshot omitted the live dithering effect");
      }
      context.runOnClient(client -> CbbgConfig.setMode(CbbgConfig.Mode.DISABLED));
      context.waitTicks(3);
      long stopped =
          context.computeOnClient(
              client -> {
                if (DitherController.isReady()
                    || Objects.requireNonNull(
                                client.gameRenderer.mainRenderTarget().getColorTexture())
                            .getFormat()
                        != GpuFormat.RGBA8_UNORM) {
                  throw new AssertionError("Disabling did not release noise and restore RGBA8");
                }
                return DitherController.getPresentationCount();
              });
      context.waitTicks(3);
      context.runOnClient(
          client -> {
            if (DitherController.getPresentationCount() != stopped) {
              throw new AssertionError("Disabled effect still presented dithered frames");
            }
            CbbgConfig.setMode(CbbgConfig.Mode.DEMO);
          });
      context.waitFor(
          client -> DitherController.isReady() && DitherController.getPresentationCount() > stopped,
          600);
      if (!STBNCache.isCacheValid(16, 16, 8)) {
        throw new AssertionError("Generated noise cache failed checksum validation");
      }
      try {
        Path evidence =
            Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")))
                .resolve("noise-" + System.getProperty("cbbg.test.backend"));
        Files.createDirectories(evidence);
        for (int frame = 0; frame < 8; frame++) {
          String name = String.format(STBNCache.IMAGE_BASE_FMT, 16, 16, 8, frame) + ".png";
          Files.copy(
              STBNCache.CACHE_DIR.resolve(name),
              evidence.resolve(name),
              StandardCopyOption.REPLACE_EXISTING);
        }
        String name = String.format(STBNCache.HASH_FILE_FMT, 16, 16, 8);
        Files.copy(
            STBNCache.CACHE_DIR.resolve(name),
            evidence.resolve(name),
            StandardCopyOption.REPLACE_EXISTING);
      } catch (java.io.IOException failure) {
        throw new AssertionError("Could not retain generated noise evidence", failure);
      }
    } finally {
      context.runOnClient(client -> CbbgConfig.setMode(original));
    }
  }
}
