package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;

/** Retains startup timing and validates the packaged renderer's fixed startup cache. */
@NullMarked
public final class ReleaseEarlyStartupGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseClient.checkArtifactAndBackend(context);
    if (Boolean.getBoolean("cbbg.test.lifecycle")) {
      throw new AssertionError("Startup fixture requires cbbg.test.lifecycle=false");
    }
    assertStartupSettings();
    new EarlyStartupGameTest().runTest(context);
    assertStartupSettings();
    String location = System.getProperty("cbbg.test.evidence");
    if (location == null || location.isBlank()) {
      throw new AssertionError("cbbg.test.evidence is required");
    }
    Path game = FabricLoader.getInstance().getGameDir();
    Path evidence = game.resolve(location);
    try {
      JsonObject result = readJson(game.resolve("early-startup-result.json"));
      JsonObject preLaunch = readJson(evidence.resolve("startup-prelaunch.json"));
      long preLaunchMillis = preLaunch.get("preLaunchMillis").getAsLong();
      long preLaunchWorkers = preLaunch.get("preLaunchWorkers").getAsLong();
      if (preLaunchMillis <= 0
          || preLaunchMillis > result.get("firstDrawMillis").getAsLong()
          || preLaunchWorkers != 1) {
        throw new AssertionError("Invalid startup prelaunch observation: " + preLaunch);
      }
      if (result.get("size").getAsInt() != 16
          || result.get("depth").getAsInt() != 8
          || result.get("seed").getAsLong() != 74123) {
        throw new AssertionError("Unexpected startup cache settings: " + result);
      }
      result.addProperty("pixelsSha256", ReleaseNoiseCacheGameTest.verifySeed74123Cache());
      result.addProperty("preLaunchMillis", preLaunchMillis);
      result.addProperty("preLaunchWorkers", preLaunchWorkers);
      Files.createDirectories(evidence);
      Files.writeString(evidence.resolve("startup.json"), result + "\n");
    } catch (Exception failure) {
      throw new AssertionError("Could not validate and retain packaged startup evidence", failure);
    }
  }

  private static void assertStartupSettings() {
    ReleaseClient.assertSettings("ENABLED", "RGBA16F", 16, 8, 74123);
    JsonObject settings = ReleaseClient.settings();
    if (settings.get("strength").getAsDouble() != 2.0) {
      throw new AssertionError("Unexpected startup strength: " + settings);
    }
  }

  private static JsonObject readJson(Path path) throws Exception {
    try (var reader = Files.newBufferedReader(path)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
  }
}
