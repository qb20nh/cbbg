package com.qb20nh.cbbg.gametest;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.jspecify.annotations.NullMarked;

/** Records the already-running noise worker before Minecraft starts drawing. */
@NullMarked
public final class ReleaseStartupPreLaunch implements PreLaunchEntrypoint {
  @Override
  public void onPreLaunch() {
    long workers =
        Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.isAlive() && thread.getName().equals("cbbg-stbn"))
            .count();
    if (workers != 1)
      throw new AssertionError("Expected one prelaunch STBN worker, got " + workers);
    long millis = ManagementFactory.getRuntimeMXBean().getUptime();
    String location = System.getProperty("cbbg.test.evidence");
    if (location == null || location.isBlank()) {
      throw new AssertionError("cbbg.test.evidence is required");
    }
    Path evidence = FabricLoader.getInstance().getGameDir().resolve(location);
    try {
      Files.createDirectories(evidence);
      Files.writeString(
          evidence.resolve("startup-prelaunch.json"),
          "{\"preLaunchMillis\":" + millis + ",\"preLaunchWorkers\":" + workers + "}\n");
    } catch (Exception failure) {
      throw new AssertionError("Could not record startup prelaunch observation", failure);
    }
  }
}
