package com.qb20nh.cbbg.gametest;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Observes an already-started STBN worker without initializing Minecraft or CBBG classes. */
public final class ReleaseStartupPreLaunch implements PreLaunchEntrypoint {
  @Override
  public void onPreLaunch() {
    long preLaunchMillis = ManagementFactory.getRuntimeMXBean().getUptime();
    long preLaunchWorkers =
        Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.isAlive() && thread.getName().equals("cbbg-stbn"))
            .count();
    if (preLaunchWorkers != 1) {
      throw new AssertionError("Expected one STBN worker by prelaunch, found " + preLaunchWorkers);
    }
    String location = System.getProperty("cbbg.test.evidence");
    if (location == null || location.isBlank()) {
      throw new AssertionError("cbbg.test.evidence is required");
    }
    Path evidence = FabricLoader.getInstance().getGameDir().resolve(location);
    try {
      Files.createDirectories(evidence);
      Files.writeString(
          evidence.resolve("startup-prelaunch.json"),
          "{\"preLaunchMillis\":"
              + preLaunchMillis
              + ",\"preLaunchWorkers\":"
              + preLaunchWorkers
              + "}\n");
    } catch (Exception failure) {
      throw new AssertionError("Could not record startup prelaunch observation", failure);
    }
  }
}
