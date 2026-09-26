package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.stbn.STBNCache;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import com.qb20nh.cbbg.render.stbn.STBNLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** A cancelled result must not leave obsolete math running beside its replacement. */
@NullMarked
public final class NoiseCancellationGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    CbbgConfig original = CbbgConfig.get();
    context.waitFor(client -> DitherController.isReady(), 600);
    context.runOnClient(client -> CbbgConfig.setMode(CbbgConfig.Mode.DISABLED));
    context.waitTicks(3);
    CompletableFuture<STBNGenerator.@Nullable STBNFields> old = null;
    try {
      assertForcedImagesReplaceCache(original.stbnSeed());
      old = STBNGenerator.generateAsync(64, 64, 32, 913725L);
      Thread worker = null;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (worker == null && !old.isDone() && System.nanoTime() < deadline) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
          if (generating(thread)) {
            worker = thread;
            break;
          }
        }
        if (worker == null) Thread.sleep(1);
      }
      if (worker == null) {
        throw new AssertionError(
            "Did not observe an active noise worker; cancellation was not exercised");
      }
      var replacement = STBNGenerator.generateAsync(16, 16, 8, 913726L);
      var fields = replacement.get(10, TimeUnit.SECONDS);
      if (!old.isCancelled()
          || fields == null
          || fields.seed() != 913726L
          || fields.uField().length != 16 * 16 * 8
          || fields.vField().length != 16 * 16 * 8) {
        throw new AssertionError(
            "Replacement generation did not complete with its requested identity");
      }
      if (generating(worker)) {
        throw new AssertionError("Superseded noise math still runs after replacement completion");
      }
    } catch (Exception failure) {
      throw new AssertionError("Noise cancellation failed", failure);
    } finally {
      if (old != null) old.cancel(true);
      context.runOnClient(client -> CbbgConfig.setMode(original.mode()));
    }
    context.waitFor(client -> DitherController.isReady(), 600);
  }

  private static void assertForcedImagesReplaceCache(long seed) throws Exception {
    Path image =
        STBNCache.CACHE_DIR.resolve(String.format(STBNCache.IMAGE_BASE_FMT, 2, 2, 1, 0) + ".png");
    Path manifest = STBNCache.CACHE_DIR.resolve(String.format(STBNCache.HASH_FILE_FMT, 2, 2, 1));
    if (Files.exists(image) || Files.exists(manifest)) {
      throw new AssertionError("Forced noise fixture requires an unused cache");
    }
    try {
      int[] stale = readFixture(new STBNGenerator.STBNFields(new double[4], new double[4], seed));
      if (!STBNCache.isCacheValid(2, 2, 1, seed)) {
        throw new AssertionError("Forced noise fixture did not create a valid cache");
      }
      var fields =
          Objects.requireNonNull(
              STBNGenerator.generateAsync(2, 2, 1, seed, true).get(10, TimeUnit.SECONDS));
      int[] fresh = readFixture(fields);
      for (int i = 0; i < fresh.length; i++) {
        if (fresh[i] != STBNGenerator.calculatePixelColor(fields.uField()[i], fields.vField()[i])) {
          throw new AssertionError("Forced loading reused cached pixels");
        }
      }
      if (Arrays.equals(stale, fresh)) {
        throw new AssertionError("Forced noise fixture did not replace stale pixels");
      }
      byte[] hashes = Files.readAllBytes(manifest);
      var modified = Files.getLastModifiedTime(image);
      if (!Arrays.equals(fresh, readFixture(null))
          || !Arrays.equals(hashes, Files.readAllBytes(manifest))
          || !modified.equals(Files.getLastModifiedTime(image))) {
        throw new AssertionError("Normal loading did not reuse regenerated cache");
      }
    } finally {
      Files.deleteIfExists(image);
      Files.deleteIfExists(manifest);
    }
  }

  private static int[] readFixture(STBNGenerator.@Nullable STBNFields fields) {
    NativeImage[] images = Objects.requireNonNull(STBNLoader.loadOrGenerate(2, 2, 1, fields));
    try {
      return new int[] {
        images[0].getPixel(0, 0), images[0].getPixel(1, 0),
        images[0].getPixel(0, 1), images[0].getPixel(1, 1)
      };
    } finally {
      for (NativeImage image : images) image.close();
    }
  }

  private static boolean generating(Thread thread) {
    for (StackTraceElement frame : thread.getStackTrace()) {
      if (frame.getClassName().equals("com.qb20nh.cbbg.math.BlueNoise")) return true;
    }
    return false;
  }
}
