package com.qb20nh.cbbg.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ReleaseGeneratingShutdownGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
    }
    assertNoOutput();
    context.runOnClient(
        client -> {
          var dispatcher = Objects.requireNonNull(ReleaseCommands.getActiveDispatcher());
          var source = ReleaseGenerationGameTest.silentSource();
          ReleaseGenerationGameTest.command(dispatcher, source, "mode set disabled");
          ReleaseGenerationGameTest.command(dispatcher, source, "stbn size 256");
          ReleaseGenerationGameTest.command(dispatcher, source, "stbn depth 128");
          ReleaseGenerationGameTest.command(dispatcher, source, "stbn seed 542319777");
          ReleaseGenerationGameTest.command(dispatcher, source, "stbn generate");
        });
    context.waitFor(client -> mathActive() && !ReleaseGenerationGameTest.pending().isDone(), 600);
    context.runOnClient(
        client -> {
          if (!mathActive())
            throw new AssertionError("Shutdown test requires active noise computation");
          ReleaseShutdownGameTest.observeGeneration(ReleaseGenerationGameTest.pending());
        });
  }

  static boolean mathActive() {
    for (var entry : Thread.getAllStackTraces().entrySet()) {
      if (!entry.getKey().getName().equals("cbbg-stbn") || !entry.getKey().isAlive()) continue;
      for (var frame : entry.getValue()) {
        if (frame.getClassName().equals("java.util.TimSort")) return true;
      }
    }
    return false;
  }

  static void assertNoOutput() {
    Path cache = FabricLoader.getInstance().getGameDir().resolve(".cbbg");
    if (!Files.exists(cache)) return;
    try (var files = Files.walk(cache)) {
      if (files.anyMatch(
          path ->
              Objects.requireNonNull(path.getFileName())
                  .toString()
                  .startsWith("stbn_256x256x128"))) {
        throw new AssertionError("Shutdown generation created cache or temporary output");
      }
    } catch (IOException failure) {
      throw new AssertionError("Could not inspect shutdown generation cache", failure);
    }
  }
}
