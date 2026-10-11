package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;

/** Dedicated, slow validation of the maximum CPU-generated noise cache and its cache-hit reload. */
@NullMarked
public final class ReleaseMaximumNoiseCacheGameTest implements FabricClientGameTest {
  private static final int SIZE = 256;
  private static final int DEPTH = 128;
  private static final long SEED = 913727L;
  private static final int WARM_WAIT_TICKS = 600;
  private static final int COLD_WAIT_TICKS = 6000;
  private static final String CPU_RGBA_HASH =
      "f366482ef363e7dd2d10cf46ab72f17d9ae6d901362c13a0eb45f8b93621d9d9";
  private static final String GENERATING = "Starting Async STBN Math Generation (256x256x128)";
  private static final String GENERATED = "STBN Images generated from math fields.";
  private static final String CACHE_HIT =
      "Valid STBN cache found for 256x256x128. Skipping math generation.";
  private static final String LOADED = "STBN Frames loaded from cache.";
  private static final String CACHE_CHECK = "Checking STBN cache (256x256x128)";
  private static final String PREPARATION_PREFIX = "STBN preparation complete in ";

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    JsonObject original =
        context.computeOnClient(client -> ReleaseWorldPixelsGameTest.currentSettings());
    FabricClientCommandSource source = silentSource();
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      try {
        command(context, source, "mode set disabled");
        command(context, source, "stbn size " + SIZE);
        command(context, source, "stbn depth " + DEPTH);
        command(context, source, "stbn seed " + SEED);
        assertSettings("DISABLED", SIZE, DEPTH, SEED);
        assertColdCache();

        long coldLogOffset = logSize();
        command(context, source, "mode set enabled");
        context.waitFor(
            client -> hasColdCompletion(coldLogOffset) && manifestComplete(), COLD_WAIT_TICKS);
        String coldLog = logSince(coldLogOffset);
        if (coldLog.contains(PREPARATION_PREFIX)
            ? !preparationCompleted(coldLog, "generated")
            : !(coldLog.contains(GENERATING) && coldLog.contains(GENERATED))) {
          throw new AssertionError("Cold maximum cache did not complete CPU generation");
        }
        CacheEvidence cold = verifyCache();
        awaitDithering(context, SIZE);
        List<NativeImage> coldFrames = context.computeOnClient(client -> loadedFrames(DEPTH));

        command(context, source, "mode set disabled");
        context.waitFor(client -> framesClosed(coldFrames), WARM_WAIT_TICKS);
        command(context, source, "stbn size 16");
        command(context, source, "stbn depth 8");
        command(context, source, "mode set enabled");
        awaitDithering(context, 16);
        List<NativeImage> smallFrames = context.computeOnClient(client -> loadedFrames(8));
        command(context, source, "mode set disabled");
        context.waitFor(client -> framesClosed(smallFrames), WARM_WAIT_TICKS);
        command(context, source, "stbn size " + SIZE);
        command(context, source, "stbn depth " + DEPTH);
        FileTime[] timestamps = ageCache();
        long warmLogOffset = logSize();
        command(context, source, "mode set enabled");
        context.waitFor(
            client ->
                (logContainsSince(warmLogOffset, CACHE_HIT)
                        || (logContainsSince(warmLogOffset, CACHE_CHECK)
                            && preparationCompleted(logSince(warmLogOffset), "cache")))
                    && logContainsSince(warmLogOffset, LOADED),
            WARM_WAIT_TICKS);
        String warmLog = logSince(warmLogOffset);
        if (warmLog.contains(GENERATING)
            || (warmLog.contains(CACHE_CHECK) && preparationCompleted(warmLog, "generated"))) {
          throw new AssertionError("Warm maximum-cache reload unexpectedly generated noise");
        }
        CacheEvidence warm = verifyCache();
        awaitDithering(context, SIZE);
        List<NativeImage> warmFrames = context.computeOnClient(client -> loadedFrames(DEPTH));
        assertSettings("ENABLED", SIZE, DEPTH, SEED);
        if (!cold.equals(warm)) {
          throw new AssertionError("Warm reload changed maximum-cache content or digest evidence");
        }
        assertTimestamps(timestamps);
        command(context, source, "mode set disabled");
        context.waitFor(client -> framesClosed(warmFrames), WARM_WAIT_TICKS);
        writeEvidence(cold);
      } finally {
        restore(context, source, original);
      }
    }
  }

  private static void awaitDithering(ClientGameTestContext context, int size) {
    context.waitFor(client -> ReleaseGenerationStatus.settled(size), WARM_WAIT_TICKS);
    long before = context.computeOnClient(client -> (Long) controllerValue("long presentations"));
    context.waitFor(
        client -> (Long) controllerValue("long presentations") > before, WARM_WAIT_TICKS);
  }

  private static List<NativeImage> loadedFrames(int depth) {
    NativeImage[] frames =
        (NativeImage[]) controllerValue("com.mojang.blaze3d.platform.NativeImage[] frames");
    if (frames.length != depth) {
      throw new AssertionError("Applied noise frame count differs from the requested depth");
    }
    return List.copyOf(Arrays.asList(frames.clone()));
  }

  private static Object controllerValue(String signature) {
    String owner = "com.qb20nh.cbbg.render.DitherController";
    try {
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      var field = type.getDeclaredField(ReleaseMapping.memberName(owner, signature));
      field.setAccessible(true);
      return Objects.requireNonNull(field.get(null));
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot inspect packaged maximum-cache lifecycle", failure);
    }
  }

  private static boolean framesClosed(List<NativeImage> frames) {
    try {
      var pixels =
          NativeImage.class.getDeclaredField(ReleaseGameNames.field(NativeImage.class, "pixels"));
      pixels.setAccessible(true);
      for (NativeImage frame : frames) {
        if (pixels.getLong(frame) != 0) return false;
      }
      return true;
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot inspect maximum-cache image disposal", failure);
    }
  }

  private static boolean hasColdCompletion(long offset) {
    String recent = logSince(offset);
    return (recent.contains(GENERATED) || preparationCompleted(recent, "generated"))
        && manifestComplete();
  }

  private static boolean preparationCompleted(String log, String result) {
    return log.lines()
        .anyMatch(line -> line.contains(PREPARATION_PREFIX) && line.contains(" (" + result + ")"));
  }

  private static void assertColdCache() {
    for (int frame = -1; frame < DEPTH; frame++) {
      if (Files.exists(path(frame))) {
        throw new AssertionError(
            "Maximum-cache test requires an isolated cold profile: " + path(frame));
      }
    }
  }

  private static boolean manifestComplete() {
    try {
      if (!Files.isRegularFile(path(-1))) return false;
      int lines = Files.readAllLines(path(-1)).size();
      return lines == DEPTH || lines == DEPTH + 1;
    } catch (IOException incomplete) {
      return false;
    }
  }

  private static CacheEvidence verifyCache() {
    try {
      List<String> manifest = Files.readAllLines(path(-1));
      boolean hasSeedHeader = manifest.getFirst().startsWith("# seed ");
      int firstEntry = hasSeedHeader ? 1 : 0;
      if (manifest.size() != DEPTH + firstEntry
          || (hasSeedHeader && !manifest.getFirst().equals("# seed " + SEED))) {
        throw new AssertionError("Maximum cache manifest is incomplete or has the wrong seed");
      }
      MessageDigest decodedDigest = MessageDigest.getInstance("SHA-256");
      MessageDigest aggregatePngDigest = MessageDigest.getInstance("SHA-256");
      byte[] pixelBytes = new byte[Integer.BYTES];
      for (int frame = 0; frame < DEPTH; frame++) {
        Path imagePath = path(frame);
        byte[] png = Files.readAllBytes(imagePath);
        aggregatePngDigest.update(png);
        String[] entry = manifest.get(frame + firstEntry).trim().split("\\s+", 0);
        String expectedName = Objects.requireNonNull(imagePath.getFileName()).toString();
        String pngHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        if (entry.length != 2 || !entry[0].equals(pngHash) || !entry[1].equals(expectedName)) {
          throw new AssertionError("Manifest SHA-256 mismatch at maximum cache frame " + frame);
        }
        try (NativeImage image = NativeImage.read(new ByteArrayInputStream(png))) {
          if (image.getWidth() != SIZE || image.getHeight() != SIZE) {
            throw new AssertionError("Maximum-cache frame has incorrect dimensions: " + frame);
          }
          for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
              int rgba = ReleaseImagePixels.argb(image, x, y);
              // The stable CPU reference hashes decoded pixels as little-endian ARGB ints.
              for (int channel = 0; channel < Integer.BYTES; channel++) {
                pixelBytes[channel] = (byte) (rgba >>> (channel * Byte.SIZE));
              }
              decodedDigest.update(pixelBytes);
            }
          }
        }
      }
      String decoded = HexFormat.of().formatHex(decodedDigest.digest());
      if (!CPU_RGBA_HASH.equals(decoded)) {
        throw new AssertionError("Decoded maximum-cache RGBA differs from the fixed CPU reference");
      }
      byte[] manifestBytes = Files.readAllBytes(path(-1));
      return new CacheEvidence(
          HexFormat.of().formatHex(aggregatePngDigest.digest()),
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)),
          decoded,
          manifest.size(),
          DEPTH * SIZE * SIZE);
    } catch (IOException failure) {
      throw new AssertionError("Could not read the maximum STBN cache", failure);
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new AssertionError("SHA-256 is unavailable", failure);
    }
  }

  private static FileTime[] ageCache() {
    FileTime[] timestamps = new FileTime[DEPTH + 1];
    try {
      for (int frame = -1; frame < DEPTH; frame++) {
        Path file = path(frame);
        Files.setLastModifiedTime(file, FileTime.fromMillis(946684800000L));
        timestamps[frame + 1] = Files.getLastModifiedTime(file);
      }
      return timestamps;
    } catch (IOException failure) {
      throw new AssertionError("Could not timestamp maximum-cache fixture files", failure);
    }
  }

  private static void assertTimestamps(FileTime[] expected) {
    try {
      for (int frame = -1; frame < DEPTH; frame++) {
        if (!expected[frame + 1].equals(Files.getLastModifiedTime(path(frame)))) {
          throw new AssertionError("Warm cache reload rewrote " + path(frame));
        }
      }
    } catch (IOException failure) {
      throw new AssertionError("Could not verify maximum-cache timestamps", failure);
    }
  }

  private static void restore(
      ClientGameTestContext context, FabricClientCommandSource source, JsonObject original) {
    context.runOnClient(
        client -> {
          command(source, "mode set disabled");
          command(source, "stbn size " + original.get("stbnSize").getAsInt());
          command(source, "stbn depth " + original.get("stbnDepth").getAsInt());
          command(source, "stbn seed " + original.get("stbnSeed").getAsLong());
          command(
              source, "mode set " + original.get("mode").getAsString().toLowerCase(Locale.ROOT));
        });
    context.waitFor(
        client -> original.equals(ReleaseWorldPixelsGameTest.currentSettings()), WARM_WAIT_TICKS);
  }

  private static void command(
      ClientGameTestContext context, FabricClientCommandSource source, String command) {
    context.runOnClient(client -> command(source, command));
  }

  private static void command(FabricClientCommandSource source, String command) {
    var dispatcher = ReleaseCommands.getActiveDispatcher();
    if (dispatcher == null) throw new AssertionError("No active client command dispatcher");
    try {
      int result = dispatcher.execute("cbbg " + command, source);
      if (result != 1)
        throw new AssertionError("Unexpected command result for " + command + ": " + result);
    } catch (CommandSyntaxException failure) {
      throw new AssertionError("Could not execute public command: " + command, failure);
    }
  }

  private static FabricClientCommandSource silentSource() {
    return (FabricClientCommandSource)
        java.lang.reflect.Proxy.newProxyInstance(
            FabricClientCommandSource.class.getClassLoader(),
            new Class<?>[] {FabricClientCommandSource.class},
            (proxy, method, args) -> {
              if (method.getName().equals("sendFeedback") || method.getName().equals("sendError")) {
                return null;
              }
              throw new AssertionError("Unexpected client command source call: " + method);
            });
  }

  private static long logSize() {
    try {
      return Files.size(logPath());
    } catch (IOException failure) {
      throw new AssertionError("Could not inspect client log", failure);
    }
  }

  private static boolean logContainsSince(long offset, String expected) {
    return logSince(offset).contains(expected);
  }

  private static String logSince(long offset) {
    try {
      Path path = logPath();
      long size = Files.size(path);
      if (size < offset) throw new AssertionError("Client log rotated during maximum-cache test");
      try (var input = Files.newInputStream(path)) {
        input.skipNBytes(offset);
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
    } catch (IOException failure) {
      throw new AssertionError("Could not read client log", failure);
    }
  }

  private static Path logPath() {
    return FabricLoader.getInstance().getGameDir().resolve("logs/latest.log");
  }

  private static Path cache() {
    return FabricLoader.getInstance().getGameDir().resolve(".cbbg");
  }

  private static Path path(int frame) {
    return cache().resolve("stbn_256x256x128" + (frame < 0 ? ".sha256" : "_" + frame + ".png"));
  }

  private static void assertSettings(String mode, int size, int depth, long seed) {
    JsonObject saved = settings();
    if (!mode.equalsIgnoreCase(saved.get("mode").getAsString())
        || size != saved.get("stbnSize").getAsInt()
        || depth != saved.get("stbnDepth").getAsInt()
        || seed != saved.get("stbnSeed").getAsLong()) {
      throw new AssertionError("Maximum-cache commands were not persisted: " + saved);
    }
  }

  private static JsonObject settings() {
    Path config = FabricLoader.getInstance().getConfigDir().resolve("cbbg.json");
    try (var reader = Files.newBufferedReader(config)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (IOException failure) {
      throw new AssertionError("Could not read persisted CBBG settings: " + config, failure);
    }
  }

  private static void writeEvidence(CacheEvidence result) {
    String location = System.getProperty("cbbg.test.evidence");
    if (location == null || location.isBlank()) {
      throw new AssertionError("cbbg.test.evidence is required for cache evidence");
    }
    JsonObject evidence = new JsonObject();
    evidence.addProperty("size", SIZE);
    evidence.addProperty("depth", DEPTH);
    evidence.addProperty("seed", SEED);
    evidence.addProperty("pngCount", DEPTH);
    evidence.addProperty("manifestLineCount", result.manifestLines());
    evidence.addProperty("width", SIZE);
    evidence.addProperty("height", SIZE);
    evidence.addProperty("decodedPixelCount", result.pixelCount());
    evidence.addProperty("aggregatePngSha256", result.aggregatePngSha256());
    evidence.addProperty("manifestSha256", result.manifestSha256());
    evidence.addProperty("decodedCpuRgbaSha256", result.decodedRgbaSha256());
    evidence.addProperty("cold", "generated");
    evidence.addProperty("warm", "cache-hit");
    evidence.addProperty("warmRewroteFiles", false);
    Path output = Path.of(location).resolve("maximum-noise-cache.json");
    try {
      Files.createDirectories(Objects.requireNonNull(output.getParent()));
      Files.writeString(output, evidence + "\n", StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new AssertionError("Could not write maximum-cache evidence", failure);
    }
  }

  private record CacheEvidence(
      String aggregatePngSha256,
      String manifestSha256,
      String decodedRgbaSha256,
      int manifestLines,
      int pixelCount) {}
}
