package com.qb20nh.cbbg.gametest;

import com.mojang.renderpearl.api.GpuFormat;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Checks one command-driven lifecycle against the processed, packaged client. */
public final class ReleaseLifecycleGameTest implements FabricClientGameTest {
  private static final long SEED = 42L;

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseClient.checkArtifactAndBackend(context);
    if (Files.exists(ReleaseClient.manifest(16, 8))) {
      throw new AssertionError("Expected a cold 16x16x8 STBN cache in the fresh game directory");
    }

    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      ReleaseClient.command(context, "mode set disabled");
      ReleaseClient.command(context, "stbn size 16");
      ReleaseClient.command(context, "stbn depth 8");
      ReleaseClient.command(context, "stbn seed " + SEED);
      ReleaseClient.command(context, "format set rgba16f");
      ReleaseClient.assertSettings("DISABLED", "RGBA16F", 16, 8, SEED);
      ReleaseClient.awaitFormat(context, GpuFormat.RGBA8_UNORM);
      ReleaseClient.assertNoDraws(context);

      ReleaseClient.command(context, "stbn generate");
      ReleaseClient.awaitCache(context, 16, 8, SEED);
      ReleaseClient.assertNoDraws(context);
      ReleaseClient.screenshot(context, "release-cold-disabled");

      long beforeEnabled = ProcessedRenderObservations.draws();
      ReleaseClient.command(context, "mode set enabled");
      ReleaseClient.awaitFormat(context, GpuFormat.RGBA16_FLOAT);
      ReleaseClient.awaitDrawAfter(context, beforeEnabled);
      ReleaseClient.assertSettings("ENABLED", "RGBA16F", 16, 8, SEED);
      if (ProcessedRenderObservations.firstDrawMillis() <= 0) {
        throw new AssertionError("No packaged CBBG pipeline draw was observed");
      }
      ReleaseClient.screenshot(context, "release-enabled-rgba16f");

      long beforeDemo = ProcessedRenderObservations.draws();
      ReleaseClient.command(context, "mode set demo");
      ReleaseClient.awaitDrawAfter(context, beforeDemo);
      ReleaseClient.assertSettings("DEMO", "RGBA16F", 16, 8, SEED);
      ReleaseClient.screenshot(context, "release-demo-rgba16f");

      long before32 = ProcessedRenderObservations.draws();
      ReleaseClient.command(context, "format set rgba32f");
      ReleaseClient.awaitFormat(context, GpuFormat.RGBA32_FLOAT);
      ReleaseClient.awaitDrawAfter(context, before32);
      ReleaseClient.assertSettings("DEMO", "RGBA32F", 16, 8, SEED);
      ReleaseClient.screenshot(context, "release-demo-rgba32f");

      int[] original =
          context.computeOnClient(
              client -> new int[] {client.getWindow().getWidth(), client.getWindow().getHeight()});
      int resizedWidth = original[0] == 960 ? 854 : 960;
      int resizedHeight = original[1] == 540 ? 480 : 540;
      try {
        context.getInput().resizeWindow(resizedWidth, resizedHeight);
        context.waitFor(
            client -> {
              var target = client.gameRenderer.mainRenderTarget();
              return client.getWindow().getWidth() == resizedWidth
                  && client.getWindow().getHeight() == resizedHeight
                  && target.width == resizedWidth
                  && target.height == resizedHeight;
            },
            600);
        ReleaseClient.awaitFormat(context, GpuFormat.RGBA32_FLOAT);
        ReleaseClient.screenshot(context, "release-resized-rgba32f");
      } finally {
        context.getInput().resizeWindow(original[0], original[1]);
        ReleaseClient.awaitFormat(context, GpuFormat.RGBA32_FLOAT);
      }

      var reload = context.computeOnClient(client -> client.reloadResourcePacks());
      context.waitFor(client -> reload.isDone(), 600);
      reload.join();
      context.waitFor(client -> client.gui.overlay() == null, 600);
      long afterReload = ProcessedRenderObservations.draws();
      ReleaseClient.awaitFormat(context, GpuFormat.RGBA32_FLOAT);
      ReleaseClient.awaitDrawAfter(context, afterReload);
      ReleaseClient.assertSettings("DEMO", "RGBA32F", 16, 8, SEED);
    }

    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      long afterTransition = ProcessedRenderObservations.draws();
      ReleaseClient.awaitFormat(context, GpuFormat.RGBA32_FLOAT);
      ReleaseClient.awaitDrawAfter(context, afterTransition);
      ReleaseClient.screenshot(context, "release-world-transition");

      ReleaseClient.command(context, "mode set disabled");
      ReleaseClient.awaitFormat(context, GpuFormat.RGBA8_UNORM);
      ReleaseClient.assertNoDraws(context);
      ReleaseClient.assertSettings("DISABLED", "RGBA32F", 16, 8, SEED);

      ReleaseClient.command(context, "format set rgba16f");
      ReleaseClient.awaitFormat(context, GpuFormat.RGBA8_UNORM);
      long beforeReenable = ProcessedRenderObservations.draws();
      ReleaseClient.command(context, "mode set enabled");
      ReleaseClient.awaitFormat(context, GpuFormat.RGBA16_FLOAT);
      ReleaseClient.awaitDrawAfter(context, beforeReenable);
      ReleaseClient.assertSettings("ENABLED", "RGBA16F", 16, 8, SEED);

      ReleaseClient.command(context, "stbn size 32");
      ReleaseClient.command(context, "stbn seed 43");
      ReleaseClient.assertSettings("ENABLED", "RGBA16F", 32, 8, 43);
      if (Files.exists(ReleaseClient.manifest(32, 8))) {
        throw new AssertionError("Expected uncached new STBN settings before Generate");
      }
      ReleaseClient.command(context, "stbn generate");
      ReleaseClient.awaitCache(context, 32, 8, 43);
      long afterNewCache = ProcessedRenderObservations.draws();
      ReleaseClient.awaitDrawAfter(context, afterNewCache);
      ReleaseClient.awaitFormat(context, GpuFormat.RGBA16_FLOAT);
      ReleaseClient.screenshot(context, "release-new-settings-generated");
    }
  }
}
