package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Exercises the packaged renderer's normal cache loading through public commands. */
@NullMarked
public final class ReleaseNoiseCacheGameTest implements FabricClientGameTest {
  private static final int SIZE = 16;
  private static final int DEPTH = 8;
  private static final int WAIT_TICKS = 600;
  // CPU reference: BlueNoise.generateScalarField U/V fields and calculatePixelColor, compiled
  // offline.
  // SHA-256 of every decoded ARGB int in z/y/x order, encoded big-endian.
  private static final String SEED_ZERO_PIXELS =
      "dcf55d060ed318039593d89bb7402af589bc818f6b64b6e90cf99ee33ccc0bcd";
  private static final String SEED_OTHER_PIXELS =
      "4f953f23c7a2a7de8960caa4272090458b2ec986ceeea65796467a0c9109a076";

  @Override
  public void runTest(ClientGameTestContext context) {
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      JsonObject original = ReleaseClient.settings().deepCopy();
      try {
        ReleaseClient.disableNoise(context);
        ReleaseClient.noiseSetting(context, "size", SIZE);
        ReleaseClient.noiseSetting(context, "depth", DEPTH);
        int[] zero = null;
        for (long seed : new long[] {0, 74123}) {
          ReleaseClient.noiseSetting(context, "seed", seed);
          clearFixture();
          int[] cold = load(context, seed);
          String expected = seed == 0 ? SEED_ZERO_PIXELS : SEED_OTHER_PIXELS;
          if (!expected.equals(pixelHash(cold))) {
            throw new AssertionError("Cold noise differs from CPU reference for seed " + seed);
          }
          if (seed == 0) zero = cold;
          else if (Arrays.equals(zero, cold)) {
            throw new AssertionError("Changing the STBN seed did not change decoded pixels");
          }

          ReleaseClient.disableNoise(context);
          byte[] manifest = Files.readAllBytes(path(-1));
          // A fixed old timestamp also detects deterministic rewrites on coarse filesystems.
          FileTime[] modified = new FileTime[DEPTH + 1];
          for (int z = -1; z < DEPTH; z++) {
            Files.setLastModifiedTime(path(z), FileTime.fromMillis(946684800000L));
            modified[z + 1] = Files.getLastModifiedTime(path(z));
          }
          assertPixels(cold, load(context, seed), "warm cache");
          if (!Arrays.equals(manifest, Files.readAllBytes(path(-1)))) {
            throw new AssertionError("Warm cache manifest was rewritten");
          }
          for (int z = -1; z < DEPTH; z++) {
            if (!modified[z + 1].equals(Files.getLastModifiedTime(path(z)))) {
              throw new AssertionError("Warm cache file was rewritten: " + path(z));
            }
          }

          ReleaseClient.disableNoise(context);
          Files.write(path(DEPTH - 1), new byte[] {1, 2, 3});
          assertPixels(cold, load(context, seed), "corrupt later PNG recovery");
          ReleaseClient.disableNoise(context);
          Files.delete(path(DEPTH - 1));
          assertPixels(cold, load(context, seed), "missing PNG recovery");
          ReleaseClient.disableNoise(context);
          Files.writeString(path(-1), "invalid manifest\n");
          assertPixels(cold, load(context, seed), "corrupt manifest recovery");
          ReleaseClient.disableNoise(context);
        }
        // Reuse the other seed's valid cache: this must reject its manifest and regenerate.
        ReleaseClient.noiseSetting(context, "seed", 0);
        if (zero == null) throw new AssertionError("Seed-zero reference was not captured");
        assertPixels(zero, load(context, 0), "changed seed recovery");
      } catch (Exception failure) {
        throw new AssertionError("Packaged noise cache lifecycle failed", failure);
      } finally {
        if (ReleaseClient.restoreNoiseSettings(context, original)) awaitNoise(context, null);
      }
    }
  }

  private static int[] load(ClientGameTestContext context, long seed) throws Exception {
    GpuTextureView old =
        context.computeOnClient(client -> ProcessedRenderObservations.lastDitherNoise());
    ReleaseClient.command(context, "mode set enabled");
    context.waitFor(
        client -> "enabled".equalsIgnoreCase(ReleaseClient.settings().get("mode").getAsString()),
        WAIT_TICKS);
    awaitNoise(context, old);
    return readCache(seed);
  }

  // A new noise view must replace the previous GPU resource.
  @SuppressWarnings("ReferenceEquality")
  private static void awaitNoise(ClientGameTestContext context, @Nullable GpuTextureView old) {
    long before = ProcessedRenderObservations.draws();
    context.waitFor(
        client -> {
          GpuTextureView noise = ProcessedRenderObservations.lastDitherNoise();
          return noise != null
              && noise != old
              && !noise.texture().isClosed()
              && noise.getWidth(0) == ReleaseClient.settings().get("stbnSize").getAsInt()
              && noise.getHeight(0) == ReleaseClient.settings().get("stbnSize").getAsInt()
              && ProcessedRenderObservations.draws() > before;
        },
        WAIT_TICKS);
  }

  private static void clearFixture() throws Exception {
    for (int z = -1; z < DEPTH; z++) Files.deleteIfExists(path(z));
  }

  private static Path path(int frame) {
    return ReleaseClient.cache()
        .resolve(frame < 0 ? "stbn_16x16x8.sha256" : "stbn_16x16x8_" + frame + ".png");
  }

  private static int[] readCache(long seed) throws Exception {
    List<String> manifest = Files.readAllLines(path(-1));
    if (manifest.size() != DEPTH + 1 || !manifest.getFirst().equals("# seed " + seed)) {
      throw new AssertionError("Incomplete cache manifest or incorrect seed " + seed);
    }
    int[] pixels = new int[SIZE * SIZE * DEPTH];
    for (int z = 0; z < DEPTH; z++) {
      byte[] bytes = Files.readAllBytes(path(z));
      String[] entry = manifest.get(z + 1).trim().split("\\s+", 0);
      if (entry.length != 2
          || !entry[0].equals(hash(bytes))
          || !entry[1].equals(Objects.requireNonNull(path(z).getFileName()).toString())) {
        throw new AssertionError("Incorrect cache PNG hash for frame " + z);
      }
      try (NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes))) {
        if (image.getWidth() != SIZE || image.getHeight() != SIZE) {
          throw new AssertionError("Incorrect noise dimensions for frame " + z);
        }
        for (int y = 0; y < SIZE; y++) {
          for (int x = 0; x < SIZE; x++) {
            pixels[(z * SIZE + y) * SIZE + x] = image.getPixel(x, y);
          }
        }
      }
    }
    return pixels;
  }

  static String verifySeed74123Cache() throws Exception {
    String actual = pixelHash(readCache(74123));
    if (!SEED_OTHER_PIXELS.equals(actual)) {
      throw new AssertionError("Startup noise differs from CPU reference for seed 74123");
    }
    return actual;
  }

  private static String pixelHash(int[] pixels) throws Exception {
    ByteBuffer bytes =
        ByteBuffer.allocate(pixels.length * Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
    for (int pixel : pixels) bytes.putInt(pixel);
    return hash(bytes.array());
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static void assertPixels(int[] expected, int[] actual, String operation) {
    if (!Arrays.equals(expected, actual)) {
      throw new AssertionError(operation + " changed decoded noise pixels");
    }
  }
}
