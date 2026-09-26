package com.qb20nh.cbbg.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** Returns while real uncached STBN math is active; cancellation belongs to Minecraft.close. */
public final class ReleaseGeneratingShutdownGameTest implements FabricClientGameTest {
  private static final String PREFIX = "stbn_128x128x128";

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseClient.checkArtifactAndBackend(context);
    assertNoJobOutput();
    GenerationObserver observer = new GenerationObserver();
    boolean armed = false;
    try {
      try (var world = context.worldBuilder().create()) {
        world.getConnection().waitForChunksRender();
        ReleaseClient.command(context, "mode set disabled");
        ReleaseClient.command(context, "stbn size 128");
        ReleaseClient.command(context, "stbn depth 128");
        long seed = 542319777L;
        ReleaseClient.command(context, "stbn seed " + seed);
        var settings = ReleaseClient.settings();
        if (settings.get("stbnSize").getAsInt() != 128
            || settings.get("stbnDepth").getAsInt() != 128
            || settings.get("stbnSeed").getAsLong() != seed) {
          throw new AssertionError("Maximum shutdown job settings were not saved");
        }
        ReleaseClient.command(context, "stbn generate");
        context.waitFor(client -> observer.mathActive(), 600);
      }
      // Closing the world must not cancel this job; prove math remains active
      // after the transition before returning control to Fabric's shutdown.
      context.waitFor(client -> observer.mathActive(), 600);
      context.runOnClient(client -> ReleaseShutdownGameTest.armGenerating(observer));
      armed = true;
    } finally {
      if (!armed) observer.close();
    }
  }

  static void assertNoJobOutput() {
    Path cache = ReleaseClient.cache();
    if (!Files.exists(cache)) return;
    try (var files = Files.walk(cache)) {
      Path output =
          files
              .filter(path -> path.getFileName().toString().startsWith(PREFIX))
              .findFirst()
              .orElse(null);
      if (output != null)
        throw new AssertionError("Shutdown job left cache or temporary output: " + output);
    } catch (IOException failure) {
      throw new AssertionError("Could not inspect shutdown job cache", failure);
    }
  }

  static final class GenerationObserver extends AbstractAppender implements AutoCloseable {
    private final Logger logger = (Logger) LogManager.getLogger("cbbg-gen");
    volatile Thread worker;
    volatile int completions;

    GenerationObserver() {
      super("cbbg-release-generating-shutdown", null, null, false, Property.EMPTY_ARRAY);
      start();
      logger.addAppender(this);
    }

    @Override
    public void append(LogEvent event) {
      if (!event.getThreadName().equals("cbbg-stbn")) return;
      String message = event.getMessage().getFormattedMessage();
      if (message.startsWith("Starting Async STBN Math Generation (128x128x128)")) {
        worker = Thread.currentThread();
      } else if (worker != null && message.startsWith("STBN Math Complete in ")) {
        completions++;
      }
    }

    boolean mathActive() {
      Thread thread = worker;
      if (thread == null || completions != 0) return false;
      for (StackTraceElement frame : thread.getStackTrace()) {
        if (frame.getClassName().equals("java.util.TimSort")) return true;
      }
      return false;
    }

    @Override
    public void close() {
      logger.removeAppender(this);
      stop();
    }
  }
}
