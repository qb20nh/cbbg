package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Dedicated slow test of the maximum cache and actual packaged GPU presentations. */
@NullMarked
public final class ReleaseMaximumNoiseCacheGameTest implements FabricClientGameTest {
  private static final int SIZE = 256;
  private static final int DEPTH = 128;
  private static final long SEED = 913727L;
  private static final int WAIT_TICKS = 600;
  // Allow six minutes for the original fixture's 300-second generation plus PNG/upload work.
  private static final int GENERATION_WAIT_TICKS = 7200;
  private static final String PIXEL_HASH =
      "f366482ef363e7dd2d10cf46ab72f17d9ae6d901362c13a0eb45f8b93621d9d9";
  private static final Pattern FRAME = Pattern.compile("\\bstbn=(\\d+)/(\\d+)\\b");
  private static final String GENERATING = "Starting Async STBN Math Generation (256x256x128)";
  private static final String CACHE_HIT =
      "Valid STBN cache found for 256x256x128. Skipping math generation.";

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseClient.checkArtifactAndBackend(context);
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      // Saving the current user mode writes defaults omitted from the startup JSON.
      String debug =
          context.computeOnClient(client -> String.join(" ", ReleaseDebugState.read(client)));
      var modeMatch = Pattern.compile("\\buser=(ENABLED|DISABLED|DEMO)\\b").matcher(debug);
      if (!modeMatch.find()) throw new AssertionError("Missing user mode in packaged debug entry");
      ReleaseClient.command(context, "mode set " + modeMatch.group(1));
      JsonObject original = ReleaseClient.settings().deepCopy();
      try {
        ReleaseClient.disableNoise(context);
        for (int z = -1; z < DEPTH; z++) {
          if (Files.exists(path(z))) {
            throw new AssertionError("Maximum-size fixture requires a cold cache: " + path(z));
          }
        }
        ReleaseClient.noiseSetting(context, "size", SIZE);
        ReleaseClient.noiseSetting(context, "depth", DEPTH);
        ReleaseClient.noiseSetting(context, "seed", SEED);
        long coldLog = Files.size(log());
        enable(context, GENERATION_WAIT_TICKS);
        int[][] pixels = readCache();
        if (!logSince(coldLog).contains(GENERATING)) {
          throw new AssertionError("Cold maximum-size load did not report math generation");
        }
        ReleaseClient.disableNoise(context);
        byte[] manifest = Files.readAllBytes(path(-1));
        FileTime[] modified = new FileTime[DEPTH + 1];
        for (int z = -1; z < DEPTH; z++) {
          Files.setLastModifiedTime(path(z), FileTime.fromMillis(946684800000L));
          modified[z + 1] = Files.getLastModifiedTime(path(z));
        }
        long warmLog = Files.size(log());
        enable(context, WAIT_TICKS);
        readCache();
        String warmMessages = logSince(warmLog);
        if (!warmMessages.contains(CACHE_HIT) || warmMessages.contains(GENERATING)) {
          throw new AssertionError("Warm maximum-size load did not skip math generation");
        }
        if (!Arrays.equals(manifest, Files.readAllBytes(path(-1)))) {
          throw new AssertionError("Warm maximum-size manifest was rewritten");
        }
        for (int z = -1; z < DEPTH; z++) {
          if (!modified[z + 1].equals(Files.getLastModifiedTime(path(z)))) {
            throw new AssertionError("Warm maximum-size file was rewritten: " + path(z));
          }
        }
        assertCycle(context, pixels);
        ReleaseClient.disableNoise(context);
      } catch (Exception failure) {
        throw new AssertionError("Packaged maximum-size noise cache lifecycle failed", failure);
      } finally {
        if (ReleaseClient.restoreNoiseSettings(context, original))
          awaitNoise(context, null, GENERATION_WAIT_TICKS);
      }
    }
  }

  private static void enable(ClientGameTestContext context, int timeout) {
    GpuTextureView old =
        context.computeOnClient(client -> ProcessedRenderObservations.lastDitherNoise());
    ReleaseClient.command(context, "mode set enabled");
    context.waitFor(
        client -> "enabled".equalsIgnoreCase(ReleaseClient.settings().get("mode").getAsString()),
        WAIT_TICKS);
    awaitNoise(context, old, timeout);
  }

  // A new noise view must replace the previous GPU resource.
  @SuppressWarnings("ReferenceEquality")
  private static void awaitNoise(
      ClientGameTestContext context, @Nullable GpuTextureView old, int timeout) {
    long before = ProcessedRenderObservations.draws();
    context.waitFor(
        client -> {
          GpuTextureView noise = ProcessedRenderObservations.lastDitherNoise();
          JsonObject settings = ReleaseClient.settings();
          return client.gui.overlay() == null
              && noise != null
              && noise != old
              && !noise.texture().isClosed()
              && noise.getWidth(0) == settings.get("stbnSize").getAsInt()
              && noise.getHeight(0) == settings.get("stbnSize").getAsInt()
              && ProcessedRenderObservations.draws() > before;
        },
        timeout);
  }

  private static int[][] readCache() throws Exception {
    var manifest = Files.readAllLines(path(-1));
    if (manifest.size() != DEPTH + 1 || !manifest.getFirst().equals("# seed " + SEED)) {
      throw new AssertionError("Incomplete maximum-size manifest or incorrect seed");
    }
    int[][] pixels = new int[DEPTH][];
    MessageDigest decoded = MessageDigest.getInstance("SHA-256");
    byte[] pixel = new byte[4];
    for (int z = 0; z < DEPTH; z++) {
      byte[] png = Files.readAllBytes(path(z));
      String[] entry = manifest.get(z + 1).trim().split("\\s+", 0);
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
      if (entry.length != 2
          || !entry[0].equals(hash)
          || !entry[1].equals(Objects.requireNonNull(path(z).getFileName()).toString())) {
        throw new AssertionError("Incorrect maximum-size PNG hash for frame " + z);
      }
      try (var input = new java.io.ByteArrayInputStream(png);
          NativeImage image = NativeImage.read(input)) {
        if (image.getWidth() != SIZE || image.getHeight() != SIZE) {
          throw new AssertionError("Incorrect maximum-size PNG dimensions for frame " + z);
        }
        pixels[z] = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
          for (int x = 0; x < SIZE; x++) {
            int value = image.getPixel(x, y);
            pixels[z][y * SIZE + x] = value;
            // Original maximum fixture hashes little-endian ints in z/y/x order.
            for (int channel = 0; channel < 4; channel++)
              pixel[channel] = (byte) (value >>> (channel * 8));
            decoded.update(pixel);
          }
        }
      }
    }
    if (!PIXEL_HASH.equals(HexFormat.of().formatHex(decoded.digest()))) {
      throw new AssertionError("Maximum-size decoded pixels differ from CPU reference");
    }
    return pixels;
  }

  private static void assertCycle(ClientGameTestContext context, int[][] expected) {
    var samples = new ArrayList<Sample>();
    CompletableFuture<@Nullable Void> failure = new CompletableFuture<>();
    try {
      context.runOnClient(
          client ->
              ProcessedRenderObservations.setNoiseObserver(
                  noise -> {
                    if (samples.size() >= DEPTH + 1 || failure.isDone()) return;
                    try {
                      int frame = debugFrame(client);
                      if (frame < 0
                          || noise == null
                          || noise.texture().isClosed()
                          || noise.getWidth(0) != SIZE
                          || noise.getHeight(0) != SIZE) {
                        throw new AssertionError("Maximum-size GPU presentation fell back");
                      }
                      long presentations = ProcessedRenderObservations.presentations();
                      CompletableFuture<int[]> pixels = readNoise(noise);
                      if (ProcessedRenderObservations.presentations() != presentations
                          || debugFrame(client) != frame) {
                        throw new AssertionError(
                            "Maximum-size noise probe advanced the temporal sequence");
                      }
                      samples.add(new Sample(presentations, frame, pixels));
                    } catch (Throwable problem) {
                      failure.completeExceptionally(problem);
                    }
                  }));
      context.waitFor(client -> samples.size() == DEPTH + 1 || failure.isDone(), WAIT_TICKS);
      if (failure.isDone()) failure.join();
      context.waitFor(
          client -> samples.stream().allMatch(sample -> sample.pixels().isDone()), WAIT_TICKS);
    } finally {
      context.runOnClient(client -> ProcessedRenderObservations.setNoiseObserver(null));
    }
    boolean[] seen = new boolean[DEPTH];
    Sample previous = null;
    for (int i = 0; i < samples.size(); i++) {
      Sample next = samples.get(i);
      if (previous != null
          && (next.presentations() != previous.presentations() + 1
              || next.frame() != (previous.frame() + 1) % DEPTH)) {
        throw new AssertionError(
            "Maximum-size actual presentations skipped or repeated a noise frame");
      }
      if (i < DEPTH) {
        if (seen[next.frame()]) throw new AssertionError("Maximum-size GPU cycle repeated early");
        seen[next.frame()] = true;
      } else if (next.frame() != samples.getFirst().frame()) {
        throw new AssertionError("Maximum-size GPU cycle did not wrap after 128 frames");
      }
      int[] actual = next.pixels().join();
      for (int y = 0; y < SIZE; y++) {
        for (int x = 0; x < SIZE; x++) {
          // The vanilla screenshot's top row corresponds to the uploaded PNG's last row.
          if (actual[y * SIZE + x] != expected[next.frame()][(SIZE - 1 - y) * SIZE + x]) {
            throw new AssertionError(
                "Maximum-size GPU noise differs from cache frame "
                    + next.frame()
                    + " at "
                    + x
                    + ","
                    + y);
          }
        }
      }
      previous = next;
    }
    if (samples.size() != DEPTH + 1)
      throw new AssertionError("Incomplete maximum-size actual GPU cycle");
    for (boolean observed : seen) {
      if (!observed) throw new AssertionError("Maximum-size GPU cycle omitted a noise frame");
    }
  }

  private static int debugFrame(Minecraft client) {
    for (String line : ReleaseDebugState.read(client)) {
      var match = FRAME.matcher(line);
      if (match.find()) {
        int frame = Integer.parseInt(Objects.requireNonNull(match.group(1)));
        if (Integer.parseInt(Objects.requireNonNull(match.group(2))) != DEPTH || frame >= DEPTH) {
          throw new AssertionError("Unexpected maximum-size debug state: " + line);
        }
        return frame;
      }
    }
    return -1;
  }

  private static CompletableFuture<int[]> readNoise(GpuTextureView noise) {
    CompletableFuture<int[]> pixels = new CompletableFuture<>();
    TextureTarget target =
        new TextureTarget("CBBG maximum noise probe", SIZE, SIZE, GpuFormat.RGBA8_UNORM, null);
    try {
      ReleaseClient.blitNoise(target, noise, "CBBG maximum noise probe");
      Screenshot.takeScreenshot(
          target,
          1,
          image -> {
            try (image) {
              if (image.getWidth() != SIZE || image.getHeight() != SIZE) {
                throw new AssertionError("Maximum-size GPU noise readback has wrong dimensions");
              }
              pixels.complete(image.getPixels());
            } catch (Throwable failure) {
              pixels.completeExceptionally(failure);
            } finally {
              target.destroyBuffers();
            }
          });
    } catch (Throwable failure) {
      target.destroyBuffers();
      pixels.completeExceptionally(failure);
    }
    return pixels;
  }

  private static Path path(int frame) {
    return ReleaseClient.cache()
        .resolve("stbn_256x256x128" + (frame < 0 ? ".sha256" : "_" + frame + ".png"));
  }

  private static Path log() {
    return FabricLoader.getInstance().getGameDir().resolve("logs/latest.log");
  }

  private static String logSince(long offset) throws Exception {
    if (Files.size(log()) < offset)
      throw new AssertionError("Client log rotated during cache observation");
    try (var input = Files.newInputStream(log())) {
      input.skipNBytes(offset);
      return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  private record Sample(long presentations, int frame, CompletableFuture<int[]> pixels) {}
}
