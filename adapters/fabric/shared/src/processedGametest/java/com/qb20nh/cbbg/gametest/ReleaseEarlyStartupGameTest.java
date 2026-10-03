package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/** Validates fixed startup settings and decoded cache pixels from the installed mod. */
@NullMarked
public final class ReleaseEarlyStartupGameTest implements FabricClientGameTest {
  private static final int SIZE = 16;
  private static final int DEPTH = 8;
  private static final long SEED = 74123;
  // SHA-256 of CPU-reference decoded ARGB pixels in z/y/x order, encoded big-endian.
  private static final String PIXELS_SHA256 =
      "4f953f23c7a2a7de8960caa4272090458b2ec986ceeea65796467a0c9109a076";

  @Override
  public void runTest(ClientGameTestContext context) {
    long titleMillis = ManagementFactory.getRuntimeMXBean().getUptime();
    if (!FabricLoader.getInstance().isModLoaded("cbbg")) {
      throw new AssertionError("Packaged CBBG is missing");
    }
    if (Boolean.getBoolean("cbbg.test.lifecycle")) {
      throw new AssertionError("Startup fixture requires cbbg.test.lifecycle=false");
    }
    String backend = Objects.requireNonNull(System.getProperty("cbbg.test.backend"));
    String[] identity = context.computeOnClient(client -> ReleaseBackend.identity());
    if (!identity[0].equalsIgnoreCase(backend)) {
      throw new AssertionError("Requested " + backend + ", received " + identity[0]);
    }
    LoggerFactory.getLogger("cbbg-renderer-test")
        .info("Readback backend={} GPU={} driver={}", identity[0], identity[1], identity[2]);
    Path game = FabricLoader.getInstance().getGameDir();
    Path evidence = game.resolve(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
    JsonObject graphics = new JsonObject();
    graphics.addProperty("backend", backend);
    graphics.addProperty("device", identity[1]);
    graphics.addProperty("driver", identity[2]);
    try {
      Files.createDirectories(evidence);
      Files.writeString(evidence.resolve("graphics-context.json"), graphics + "\n");
    } catch (Exception failure) {
      throw new AssertionError("Could not write startup graphics context", failure);
    }
    JsonObject settings = readJson(FabricLoader.getInstance().getConfigDir().resolve("cbbg.json"));
    if (!settings.get("mode").getAsString().equalsIgnoreCase("enabled")
        || !settings.get("pixelFormat").getAsString().equalsIgnoreCase("rgba16f")
        || settings.get("stbnSize").getAsInt() != SIZE
        || settings.get("stbnDepth").getAsInt() != DEPTH
        || settings.get("stbnSeed").getAsLong() != SEED
        || settings.get("strength").getAsDouble() != 2.0) {
      throw new AssertionError("Unexpected fixed startup settings: " + settings);
    }
    context.waitFor(client -> cachePixelsSha256(game) != null, 6000);
    long cacheReadyMillis = ManagementFactory.getRuntimeMXBean().getUptime();
    String pixelsSha256 = Objects.requireNonNull(cachePixelsSha256(game));
    if (!PIXELS_SHA256.equals(pixelsSha256)) {
      throw new AssertionError("Startup cache pixels differ from CPU reference: " + pixelsSha256);
    }
    context.waitFor(client -> ReleaseStartupObservations.firstDrawMillis() != 0, 600);
    long firstDrawMillis = ReleaseStartupObservations.firstDrawMillis();
    JsonObject preLaunch = readJson(evidence.resolve("startup-prelaunch.json"));
    long preLaunchMillis = preLaunch.get("preLaunchMillis").getAsLong();
    long preLaunchWorkers = preLaunch.get("preLaunchWorkers").getAsLong();
    if (preLaunchWorkers != 1
        || preLaunchMillis <= 0
        || preLaunchMillis > firstDrawMillis
        || preLaunchMillis > titleMillis) {
      throw new AssertionError("Invalid prelaunch and first-draw timing: " + preLaunch);
    }
    JsonObject result = new JsonObject();
    result.addProperty("backend", backend);
    result.addProperty("titleMillis", titleMillis);
    result.addProperty("cacheReadyMillis", cacheReadyMillis);
    result.addProperty("firstDrawMillis", firstDrawMillis);
    result.addProperty("preLaunchMillis", preLaunchMillis);
    result.addProperty("preLaunchWorkers", preLaunchWorkers);
    result.addProperty("size", SIZE);
    result.addProperty("depth", DEPTH);
    result.addProperty("seed", SEED);
    result.addProperty("pixelsSha256", pixelsSha256);
    result.addProperty("device", identity[1]);
    result.addProperty("driver", identity[2]);
    context.takeScreenshot("early-startup-" + backend);
    try {
      Files.createDirectories(evidence);
      Files.writeString(evidence.resolve("startup.json"), result + "\n");
    } catch (Exception failure) {
      throw new AssertionError("Could not write startup evidence", failure);
    }
  }

  private static JsonObject readJson(Path path) {
    try (var reader = Files.newBufferedReader(path)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception failure) {
      throw new AssertionError("Could not read " + path, failure);
    }
  }

  private static @Nullable String cachePixelsSha256(Path game) {
    Path cache = game.resolve(".cbbg");
    Path manifest = cache.resolve("stbn_16x16x8.sha256");
    if (!Files.isRegularFile(manifest)) return null;
    try {
      List<String> lines = Files.readAllLines(manifest);
      int offset = lines.size() == DEPTH + 1 && lines.getFirst().equals("# seed " + SEED) ? 1 : 0;
      if (lines.size() != DEPTH + offset) return null;
      ByteBuffer pixels =
          ByteBuffer.allocate(SIZE * SIZE * DEPTH * Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (int z = 0; z < DEPTH; z++) {
        String file = "stbn_16x16x8_" + z + ".png";
        Path imagePath = cache.resolve(file);
        if (!Files.isRegularFile(imagePath)) return null;
        byte[] bytes = Files.readAllBytes(imagePath);
        String[] entry = lines.get(z + offset).trim().split("\\s+", 0);
        if (entry.length != 2
            || !entry[0].equals(HexFormat.of().formatHex(digest.digest(bytes)))
            || !entry[1].equals(file)) return null;
        try (NativeImage image = NativeImage.read(bytes)) {
          if (image.getWidth() != SIZE || image.getHeight() != SIZE) return null;
          for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) pixels.putInt(image.getPixel(x, y));
          }
        }
      }
      return HexFormat.of().formatHex(digest.digest(pixels.array()));
    } catch (Exception failure) {
      return null;
    }
  }
}
