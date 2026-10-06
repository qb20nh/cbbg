package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Replaces real in-flight packaged noise jobs through the public client commands. */
@NullMarked
public final class ReleaseGenerationGameTest implements FabricClientGameTest {
  private static final int WAIT_TICKS = 1200;
  private static final String REPLACEMENT_PIXELS =
      "4f953f23c7a2a7de8960caa4272090458b2ec986ceeea65796467a0c9109a076";

  @Override
  // The previous future must be a different object from the replacement.
  @SuppressWarnings("ReferenceEquality")
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      JsonObject original = settings();
      CommandDispatcher<FabricClientCommandSource> dispatcher =
          Objects.requireNonNull(
              context.computeOnClient(client -> ReleaseCommands.getActiveDispatcher()));
      FabricClientCommandSource source = silentSource();
      boolean completed = false;
      try {
        context.runOnClient(client -> command(dispatcher, source, "mode set enabled"));
        CompletableFuture<?> old =
            context.computeOnClient(
                client -> {
                  configure(dispatcher, source, 256, 128, 913725);
                  command(dispatcher, source, "stbn generate");
                  return pending();
                });
        context.waitFor(
            client -> ReleaseGeneratingShutdownGameTest.mathActive() && !old.isDone(), WAIT_TICKS);
        Request replacement =
            context.computeOnClient(
                client -> {
                  if (old.isDone())
                    throw new AssertionError("Large generation finished before replacement");
                  configure(dispatcher, source, 16, 8, 74123);
                  if (old.isDone())
                    throw new AssertionError("Large generation was not still in flight");
                  command(dispatcher, source, "stbn generate");
                  CompletableFuture<?> next = pending();
                  if (next == old || !cancelled(old)) {
                    throw new AssertionError("Replacement did not cancel the in-flight generation");
                  }
                  return new Request(old, next);
                });
        context.waitFor(
            client ->
                replacement.next().isDone()
                    && !replacement.next().isCancelled()
                    && !replacement.next().isCompletedExceptionally()
                    && pending() == replacement.next()
                    && ReleaseGenerationStatus.settled(16),
            WAIT_TICKS);
        assertSettings(16, 8, 74123);
        assertCache(16, 8, 74123, REPLACEMENT_PIXELS);
        context.waitTicks(5);
        if (!cancelled(replacement.old()) || pending() != replacement.next()) {
          throw new AssertionError("Obsolete generation replaced the active result");
        }
        assertSettings(16, 8, 74123);
        assertCache(16, 8, 74123, REPLACEMENT_PIXELS);

        CompletableFuture<?> resetOld =
            context.computeOnClient(
                client -> {
                  configure(dispatcher, source, 256, 128, 913726);
                  command(dispatcher, source, "stbn generate");
                  CompletableFuture<?> large = pending();
                  if (large.isDone())
                    throw new AssertionError("Reset fixture finished before reset");
                  return large;
                });
        context.waitFor(
            client -> ReleaseGeneratingShutdownGameTest.mathActive() && !resetOld.isDone(),
            WAIT_TICKS);
        context.runOnClient(client -> command(dispatcher, source, "stbn reset"));
        assertSettings(128, 64, 0);
        context.waitFor(
            client -> {
              CompletableFuture<?> current = pending();
              return cancelled(resetOld)
                  && current != resetOld
                  && current != null
                  && current.isDone()
                  && !current.isCancelled()
                  && !current.isCompletedExceptionally()
                  && ReleaseGenerationStatus.settled(128);
            },
            WAIT_TICKS);
        assertCache(128, 64, 0, null);
        assertSettings(128, 64, 0);
        completed = true;
      } finally {
        try {
          if (!completed) {
            // Supersede any oversized work even when an assertion fails.
            context.runOnClient(
                client -> {
                  configure(dispatcher, source, 16, 8, 74123);
                  command(dispatcher, source, "stbn generate");
                });
            context.waitFor(client -> pending().isDone(), WAIT_TICKS);
          }
        } finally {
          context.runOnClient(
              client -> {
                command(dispatcher, source, "mode set disabled");
                configure(
                    dispatcher,
                    source,
                    original.get("stbnSize").getAsInt(),
                    original.get("stbnDepth").getAsInt(),
                    original.get("stbnSeed").getAsLong());
              });
          context.waitFor(
              client -> {
                JsonObject now = settings();
                return now.get("mode").getAsString().equalsIgnoreCase("disabled")
                    && now.get("stbnSize").getAsInt() == original.get("stbnSize").getAsInt()
                    && now.get("stbnDepth").getAsInt() == original.get("stbnDepth").getAsInt()
                    && now.get("stbnSeed").getAsLong() == original.get("stbnSeed").getAsLong();
              },
              200);
        }
      }
    }
  }

  static boolean cancelled(CompletableFuture<?> future) {
    if (future.isCancelled()) return true;
    if (!future.isDone()) return false;
    try {
      future.join();
      return false;
    } catch (CancellationException cancelled) {
      return true;
    } catch (CompletionException failure) {
      return failure.getCause() instanceof CancellationException;
    }
  }

  static FabricClientCommandSource silentSource() {
    return (FabricClientCommandSource)
        Proxy.newProxyInstance(
            FabricClientCommandSource.class.getClassLoader(),
            new Class<?>[] {FabricClientCommandSource.class},
            (proxy, method, args) -> {
              if (method.getName().equals("sendFeedback") || method.getName().equals("sendError")) {
                return null;
              }
              throw new AssertionError("Unexpected command source call: " + method);
            });
  }

  private static void configure(
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      int size,
      int depth,
      long seed) {
    command(dispatcher, source, "stbn size " + size);
    command(dispatcher, source, "stbn depth " + depth);
    command(dispatcher, source, "stbn seed " + seed);
  }

  static void command(
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

  static CompletableFuture<?> pending() {
    return ReleaseGenerationStatus.pending();
  }

  private static JsonObject settings() {
    Path config = FabricLoader.getInstance().getConfigDir().resolve("cbbg.json");
    try (var reader = Files.newBufferedReader(config)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception failure) {
      throw new AssertionError("Could not read packaged CBBG settings", failure);
    }
  }

  private static void assertSettings(int size, int depth, long seed) {
    JsonObject config = settings();
    if (config.get("stbnSize").getAsInt() != size
        || config.get("stbnDepth").getAsInt() != depth
        || config.get("stbnSeed").getAsLong() != seed) {
      throw new AssertionError("Unexpected noise settings: " + config);
    }
  }

  private static void assertCache(int size, int depth, long seed, @Nullable String expectedPixels) {
    Path cache = FabricLoader.getInstance().getGameDir().resolve(".cbbg");
    Path manifest = cache.resolve("stbn_" + size + "x" + size + "x" + depth + ".sha256");
    try {
      List<String> lines = Files.readAllLines(manifest);
      int offset = lines.size() == depth + 1 && lines.getFirst().equals("# seed " + seed) ? 1 : 0;
      if (lines.size() != depth + offset) {
        throw new AssertionError("Incorrect noise manifest size or seed: " + manifest);
      }
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      ByteBuffer pixels =
          expectedPixels == null
              ? null
              : ByteBuffer.allocate(size * size * depth * Integer.BYTES)
                  .order(ByteOrder.BIG_ENDIAN);
      for (int z = 0; z < depth; z++) {
        String name = "stbn_" + size + "x" + size + "x" + depth + "_" + z + ".png";
        byte[] png = Files.readAllBytes(cache.resolve(name));
        String[] entry = lines.get(z + offset).trim().split("\\s+", 0);
        if (entry.length != 2
            || !entry[1].equals(name)
            || !entry[0].equals(HexFormat.of().formatHex(digest.digest(png)))) {
          throw new AssertionError("Noise manifest differs at frame " + z);
        }
        try (NativeImage image = NativeImage.read(png)) {
          if (image.getWidth() != size || image.getHeight() != size) {
            throw new AssertionError("Noise dimensions differ at frame " + z);
          }
          if (pixels != null) {
            for (int y = 0; y < size; y++) {
              for (int x = 0; x < size; x++) pixels.putInt(image.getPixel(x, y));
            }
          }
        }
      }
      if (expectedPixels != null
          && pixels != null
          && !expectedPixels.equals(HexFormat.of().formatHex(digest.digest(pixels.array())))) {
        throw new AssertionError("Replacement decoded pixels differ from CPU reference");
      }
    } catch (Exception failure) {
      throw new AssertionError("Could not validate packaged noise cache", failure);
    }
  }

  private record Request(CompletableFuture<?> old, CompletableFuture<?> next) {}
}
