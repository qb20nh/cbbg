package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Checks the mod's resources before Minecraft closes its renderer. */
@NullMarked
public final class ReleaseShutdownGameTest implements FabricClientGameTest {
  private static GpuTextureView @Nullable [] resources;
  private static ReleaseShutdownResources.@Nullable Owned owned;
  private static @Nullable List<Thread> workers;
  private static boolean observing;
  private static @Nullable CompletableFuture<?> generation;

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      context.waitFor(client -> ReleaseGenerationStatus.settled(16), 600);
    }
    context.waitFor(client -> ReleaseShutdownResources.live() != null, 600);
    context.runOnClient(
        client -> {
          resources = Objects.requireNonNull(ReleaseShutdownResources.live());
          owned = ReleaseShutdownResources.captureOwned();
          workers = workerThreads();
          if (workers.isEmpty()) throw new AssertionError("No noise worker available at shutdown");
          observing = true;
        });
  }

  public static void observeDraw() {
    if (observing && generation == null) {
      resources = Objects.requireNonNull(ReleaseShutdownResources.live());
      owned = ReleaseShutdownResources.captureOwned();
    }
  }

  static void observeGeneration(CompletableFuture<?> pending) {
    if (pending.isDone()) throw new AssertionError("Shutdown generation has already finished");
    workers = workerThreads();
    if (workers.isEmpty()) throw new AssertionError("No generation worker to check at shutdown");
    generation = pending;
    observing = true;
  }

  private static List<Thread> workerThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.isAlive() && thread.getName().equals("cbbg-stbn"))
        .toList();
  }

  public static void beforeRendererClose() {
    if (!observing) return;
    observing = false;
    JsonObject record = new JsonObject();
    record.addProperty("kind", generation == null ? "idle" : "generating");
    record.addProperty(
        "resourcesChecked",
        generation == null
            ? 4 + ReleaseShutdownResources.ownedCount(Objects.requireNonNull(owned))
            : 0);
    record.addProperty("generationStarted", generation != null);
    Throwable failure = null;
    boolean closed = false;
    boolean stopped = false;
    try {
      closed = true;
      if (generation == null) {
        for (GpuTextureView view : Objects.requireNonNull(resources)) {
          closed &= view.isClosed() && view.texture().isClosed();
        }
        closed &= ReleaseShutdownResources.ownedClosed(Objects.requireNonNull(owned));
      } else {
        if (!generation.isCancelled())
          throw new AssertionError("Shutdown did not cancel the generation");
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      for (Thread worker : Objects.requireNonNull(workers)) {
        long remaining = deadline - System.nanoTime();
        if (worker.isAlive() && remaining > 0) TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
      }
      stopped =
          Objects.requireNonNull(workers).stream().noneMatch(Thread::isAlive)
              && workerThreads().isEmpty();
      if (!closed)
        throw new AssertionError("CBBG did not close its GPU resources and noise images");
      if (!stopped) throw new AssertionError("CBBG noise worker did not stop within 10 seconds");
      if (generation != null) ReleaseGeneratingShutdownGameTest.assertNoOutput();
    } catch (Throwable error) {
      failure = error;
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
    }
    record.addProperty("resourcesClosed", closed);
    record.addProperty("workerTerminated", stopped);
    record.addProperty(
        "generationCompleted",
        generation != null && generation.isDone() && !generation.isCancelled());
    if (failure != null) record.addProperty("failure", failure.toString());
    try {
      Path directory = Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
      Files.createDirectories(directory);
      Files.writeString(directory.resolve("shutdown.json"), record + "\n");
    } catch (Exception error) {
      throw new AssertionError("Could not save shutdown results", error);
    }
    if (failure != null) throw new AssertionError("CBBG shutdown failed", failure);
  }

  static @Nullable Object field(String owner, @Nullable Object instance, String signature) {
    try {
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      var field = type.getDeclaredField(ReleaseMapping.memberName(owner, signature));
      field.setAccessible(true);
      return field.get(instance);
    } catch (ReflectiveOperationException error) {
      throw new LinkageError("Cannot inspect packaged GPU resources", error);
    }
  }
}
