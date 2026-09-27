package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Cancels actual packaged noise math through commands, without linking production classes. */
@NullMarked
public final class ReleaseNoiseCancellationGameTest implements FabricClientGameTest {
  private static final int WAIT_TICKS = 200;
  private static final long REPLACEMENT_SEED = 74123;
  // Independent CPU reference: decoded ARGB ints in z/y/x order, big-endian.
  private static final String REPLACEMENT_PIXELS =
      "4f953f23c7a2a7de8960caa4272090458b2ec986ceeea65796467a0c9109a076";

  @Override
  // The exact replacement noise resource must survive stale completion.
  @SuppressWarnings("ReferenceEquality")
  public void runTest(ClientGameTestContext context) {
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      JsonObject original = ReleaseClient.settings().deepCopy();
      var dispatcher = context.computeOnClient(client -> ClientCommands.getActiveDispatcher());
      if (dispatcher == null) throw new AssertionError("No client command dispatcher");
      FabricClientCommandSource source =
          (FabricClientCommandSource)
              Proxy.newProxyInstance(
                  FabricClientCommandSource.class.getClassLoader(),
                  new Class<?>[] {FabricClientCommandSource.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("sendFeedback")
                        || method.getName().equals("sendError")) {
                      return null;
                    }
                    throw new AssertionError("Unexpected command source call: " + method);
                  });
      boolean completed = false;
      try (GenerationObserver observer = new GenerationObserver()) {
        try {
          context.runOnClient(
              client -> {
                execute(dispatcher, source, "mode set disabled");
                configure(dispatcher, source, 64, 32, 913725);
                execute(dispatcher, source, "mode set enabled");
                execute(dispatcher, source, "stbn generate");
              });
          // The appender never blocks the worker. The histogram's JDK sort proves
          // execution passed the start log and entered real scalar-field math.
          context.waitFor(client -> observer.largeMathActive(), WAIT_TICKS);
          long started = System.nanoTime();
          context.runOnClient(
              client -> {
                if (observer.completions.get() != 0) {
                  throw new AssertionError("Large job completed before cancellation");
                }
                configure(dispatcher, source, 16, 8, REPLACEMENT_SEED);
                execute(dispatcher, source, "stbn generate");
              });
          long before = ProcessedRenderObservations.draws();
          context.waitFor(
              client ->
                  observer.replacementStarted
                      && observer.workerIdle()
                      && ProcessedRenderObservations.draws() > before
                      && ProcessedRenderObservations.lastDitherNoise() != null
                      && !ProcessedRenderObservations.lastDitherNoise().texture().isClosed()
                      && ProcessedRenderObservations.lastDitherNoise().getWidth(0) == 16
                      && ProcessedRenderObservations.lastDitherNoise().getHeight(0) == 16,
              WAIT_TICKS);
          if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(10)) {
            throw new AssertionError("Replacement generation exceeded ten seconds");
          }
          ReleaseClient.assertSettings(
              "ENABLED", original.get("pixelFormat").getAsString(), 16, 8, REPLACEMENT_SEED);
          assertReplacementPixels();
          if (!observer.workerIdle()
              || observer.completions.get() != 1
              || observer.replacementWorker != observer.worker) {
            throw new AssertionError(
                "Obsolete math completed or worker still runs after replacement");
          }
          completed = true;
        } catch (Exception failure) {
          throw new AssertionError("Packaged noise cancellation failed", failure);
        } finally {
          try {
            if (!completed) {
              // Supersede a still-running large job even when an assertion fails.
              context.runOnClient(
                  client -> {
                    configure(dispatcher, source, 16, 8, REPLACEMENT_SEED);
                    execute(dispatcher, source, "stbn generate");
                  });
              context.waitFor(client -> observer.workerIdle(), WAIT_TICKS);
            }
          } finally {
            context.runOnClient(
                client -> {
                  execute(dispatcher, source, "mode set disabled");
                  configure(
                      dispatcher,
                      source,
                      original.get("stbnSize").getAsInt(),
                      original.get("stbnDepth").getAsInt(),
                      original.get("stbnSeed").getAsLong());
                  execute(
                      dispatcher,
                      source,
                      "mode set " + original.get("mode").getAsString().toLowerCase(Locale.ROOT));
                });
            context.waitFor(client -> original.equals(ReleaseClient.settings()), 600);
          }
        }
      }
    }
  }

  private static void configure(
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      int size,
      int depth,
      long seed) {
    execute(dispatcher, source, "stbn size " + size);
    execute(dispatcher, source, "stbn depth " + depth);
    execute(dispatcher, source, "stbn seed " + seed);
  }

  private static void execute(
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      String suffix) {
    try {
      if (dispatcher.execute("cbbg " + suffix, source) != 1) {
        throw new AssertionError("Command failed: " + suffix);
      }
    } catch (CommandSyntaxException failure) {
      throw new AssertionError("Command failed: " + suffix, failure);
    }
  }

  private static void assertReplacementPixels() throws Exception {
    Path manifest = ReleaseClient.manifest(16, 8);
    List<String> lines = Files.readAllLines(manifest);
    if (lines.size() != 9 || !lines.getFirst().equals("# seed " + REPLACEMENT_SEED)) {
      throw new AssertionError("Replacement cache identity is incorrect");
    }
    ByteBuffer pixels =
        ByteBuffer.allocate(16 * 16 * 8 * Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
    for (int z = 0; z < 8; z++) {
      String name = "stbn_16x16x8_" + z + ".png";
      byte[] bytes = Files.readAllBytes(ReleaseClient.cache().resolve(name));
      String[] entry = lines.get(z + 1).trim().split("\\s+", 0);
      if (entry.length != 2 || !entry[1].equals(name) || !entry[0].equals(hash(bytes))) {
        throw new AssertionError("Replacement PNG manifest mismatch at frame " + z);
      }
      try (NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes))) {
        if (image.getWidth() != 16 || image.getHeight() != 16) {
          throw new AssertionError("Replacement PNG dimensions differ at frame " + z);
        }
        for (int y = 0; y < 16; y++) {
          for (int x = 0; x < 16; x++) pixels.putInt(image.getPixel(x, y));
        }
      }
    }
    if (!REPLACEMENT_PIXELS.equals(hash(pixels.array()))) {
      throw new AssertionError("Replacement decoded pixels differ from CPU reference");
    }
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static final class GenerationObserver extends AbstractAppender implements AutoCloseable {
    private final Logger logger = (Logger) LogManager.getLogger("cbbg-gen");
    private volatile @Nullable Thread worker;
    private volatile @Nullable Thread replacementWorker;
    private volatile boolean replacementStarted;
    private final AtomicInteger completions = new AtomicInteger();

    private GenerationObserver() {
      super("cbbg-release-cancellation", null, null, false, Property.EMPTY_ARRAY);
      start();
      logger.addAppender(this);
    }

    @Override
    public void append(LogEvent event) {
      if (!event.getThreadName().equals("cbbg-stbn")) return;
      String message = event.getMessage().getFormattedMessage();
      if (message.startsWith("Starting Async STBN Math Generation (64x64x32)")) {
        completions.set(0);
        worker = Thread.currentThread();
      } else if (worker != null
          && message.startsWith("Starting Async STBN Math Generation (16x16x8)")) {
        replacementWorker = Thread.currentThread();
        replacementStarted = true;
      } else if (worker != null && message.startsWith("STBN Math Complete in ")) {
        completions.incrementAndGet();
      }
    }

    private boolean largeMathActive() {
      Thread thread = worker;
      if (thread == null || completions.get() != 0 || replacementStarted) return false;
      for (StackTraceElement frame : thread.getStackTrace()) {
        if (frame.getClassName().equals("java.util.TimSort")) return true;
      }
      return false;
    }

    private boolean workerIdle() {
      Thread thread = worker;
      if (thread == null) return false;
      for (StackTraceElement frame : thread.getStackTrace()) {
        if (frame.getClassName().equals("java.util.concurrent.ThreadPoolExecutor")
            && frame.getMethodName().equals("getTask")) return true;
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
