package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.stbn.STBNCache;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import com.qb20nh.cbbg.render.stbn.STBNLoader;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Dedicated slow fixture: maximum-size images, PNG cache and GPU frame cycling. */
public final class MaximumNoiseCacheGameTest implements FabricClientGameTest {
  private static final int SIZE = 256;
  private static final int DEPTH = 128;
  private static final long SEED = 913727L;
  private static final String PIXEL_HASH =
      "f366482ef363e7dd2d10cf46ab72f17d9ae6d901362c13a0eb45f8b93621d9d9";

  @Override
  public void runTest(ClientGameTestContext context) {
    CbbgConfig original = CbbgConfig.get();
    context.waitFor(client -> DitherController.isReady(), 600);
    context.runOnClient(
        client -> {
          CbbgConfig.setMode(CbbgConfig.Mode.DISABLED);
          CbbgConfig.setStbnSeed(SEED);
        });
    context.waitTicks(3);
    try {
      if (STBNCache.isCacheValid(SIZE, SIZE, DEPTH, SEED)) {
        throw new AssertionError("Maximum-size fixture requires a cold cache");
      }
      var fields = STBNGenerator.generateAsync(SIZE, SIZE, DEPTH, SEED).get(300, TimeUnit.SECONDS);
      if (fields == null) throw new AssertionError("Cold generation returned no fields");
      assertImages(STBNLoader.loadOrGenerate(SIZE, SIZE, DEPTH, fields));
      if (!STBNCache.isCacheValid(SIZE, SIZE, DEPTH, SEED)) {
        throw new AssertionError("Maximum-size PNG cache is incomplete");
      }
      if (STBNGenerator.generateAsync(SIZE, SIZE, DEPTH, SEED).get(30, TimeUnit.SECONDS) != null) {
        throw new AssertionError("Warm cache unexpectedly regenerated noise");
      }
      assertImages(STBNLoader.loadOrGenerate(SIZE, SIZE, DEPTH, null));
      context.runOnClient(
          client -> {
            CbbgConfig.setStbnSize(SIZE);
            CbbgConfig.setStbnDepth(DEPTH);
            CbbgConfig.setMode(CbbgConfig.Mode.ENABLED);
            DitherController.resetAfterToggle();
          });
      context.waitFor(client -> DitherController.isReady(), 1200);
      context.runOnClient(
          client -> {
            var input = client.gameRenderer.mainRenderTarget().getColorTextureView();
            boolean[] seen = new boolean[DEPTH];
            long before = DitherController.getPresentationCount();
            for (int frame = 0; frame < DEPTH; frame++) {
              if (DitherController.present(input) == input || !DitherController.isReady()) {
                throw new AssertionError("Maximum-size GPU presentation fell back");
              }
              int index = DitherController.getCurrentStbnFrameIndex();
              if (index < 0 || index >= DEPTH || seen[index]) {
                throw new AssertionError("Maximum-size GPU frame cycle repeated or escaped bounds");
              }
              seen[index] = true;
            }
            if (DitherController.getPresentationCount() != before + DEPTH) {
              throw new AssertionError("Maximum-size GPU presentations were skipped");
            }
            CbbgConfig.setMode(CbbgConfig.Mode.DISABLED);
            DitherController.beginFrame();
            if (DitherController.isReady()) {
              throw new AssertionError("Disabled maximum-size noise remained ready");
            }
          });
    } catch (Exception failure) {
      throw new AssertionError("Maximum-size native image/cache test failed", failure);
    } finally {
      context.runOnClient(
          client -> {
            CbbgConfig.setStbnSeed(original.stbnSeed());
            CbbgConfig.setStbnSize(original.stbnSize());
            CbbgConfig.setStbnDepth(original.stbnDepth());
            CbbgConfig.setMode(original.mode());
            DitherController.resetAfterToggle();
          });
    }
    context.waitFor(client -> DitherController.isReady(), 600);
  }

  private static void assertImages(NativeImage[] images) throws Exception {
    if (images == null) throw new AssertionError("Missing maximum-size images");
    try {
      if (images.length != DEPTH) throw new AssertionError("Wrong frame count");
      var digest = MessageDigest.getInstance("SHA-256");
      byte[] pixel = new byte[4];
      for (NativeImage image : images) {
        if (image == null || image.getWidth() != SIZE || image.getHeight() != SIZE) {
          throw new AssertionError("Wrong native image dimensions");
        }
        for (int y = 0; y < SIZE; y++) {
          for (int x = 0; x < SIZE; x++) {
            int value = image.getPixel(x, y);
            for (int channel = 0; channel < 4; channel++) {
              pixel[channel] = (byte) (value >>> (8 * channel));
            }
            digest.update(pixel);
          }
        }
      }
      if (!PIXEL_HASH.equals(HexFormat.of().formatHex(digest.digest()))) {
        throw new AssertionError("Maximum-size pixels differ from CPU reference");
      }
    } finally {
      for (NativeImage image : images) if (image != null) image.close();
    }
  }
}
