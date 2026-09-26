package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.slf4j.LoggerFactory;

/** Leaves live packaged CBBG resources for the real Minecraft.close lifecycle. */
public final class ReleaseShutdownGameTest implements FabricClientGameTest {
  private static String kind;
  private static GpuTextureView noise;
  private static GpuTextureView output;
  private static List<Thread> workers;
  private static ReleaseGeneratingShutdownGameTest.GenerationObserver generation;

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseClient.checkArtifactAndBackend(context);
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      ReleaseClient.command(context, "mode set enabled");
      long before = ProcessedRenderObservations.draws();
      ReleaseClient.awaitDrawAfter(context, before);
    }
    // Capture after the world transition, and keep observing until actual shutdown
    // so a resized/replaced attachment cannot make this check pass prematurely.
    long before = ProcessedRenderObservations.presentations();
    context.waitFor(client -> ProcessedRenderObservations.presentations() > before, 600);
    context.runOnClient(
        client -> {
          captureLiveResources();
          workers = workerThreads();
          if (workers.isEmpty()) throw new AssertionError("No CBBG worker to check at shutdown");
          kind = "idle";
          ProcessedRenderObservations.setNoiseObserver(ignored -> captureLiveResources());
        });
    // Returning lets Fabric finish its active test and shut Minecraft down normally.
  }

  private static void captureLiveResources() {
    GpuTextureView observedNoise = ProcessedRenderObservations.lastDitherNoise();
    GpuTextureView observedOutput = ProcessedRenderObservations.lastDitherOutput();
    if (observedNoise == null
        || observedOutput == null
        || observedNoise.isClosed()
        || observedNoise.texture().isClosed()
        || observedOutput.isClosed()
        || observedOutput.texture().isClosed()) {
      throw new AssertionError("Expected live CBBG noise and dither output resources");
    }
    noise = observedNoise;
    output = observedOutput;
  }

  static void armGenerating(ReleaseGeneratingShutdownGameTest.GenerationObserver observer) {
    if (observer.worker == null || !observer.worker.isAlive() || observer.completions != 0) {
      throw new AssertionError("Shutdown job is no longer active");
    }
    workers = workerThreads();
    if (!workers.contains(observer.worker)) throw new AssertionError("Missing active CBBG worker");
    generation = observer;
    kind = "generating";
  }

  private static List<Thread> workerThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.isAlive() && thread.getName().equals("cbbg-stbn"))
        .toList();
  }

  /** Called after the product HEAD hook and before any vanilla GPU cleanup. */
  public static void beforeVanillaClose() {
    if (kind == null) return;
    ProcessedRenderObservations.setNoiseObserver(null);
    JsonObject evidence = new JsonObject();
    evidence.addProperty("kind", kind);
    evidence.addProperty("resourcesChecked", generation == null ? 4 : 0);
    evidence.addProperty("generationStarted", generation != null && generation.worker != null);
    boolean resourcesClosed = false;
    boolean workerTerminated = false;
    Throwable failure = null;
    try {
      resourcesClosed =
          generation != null
              || noise.isClosed()
                  && noise.texture().isClosed()
                  && output.isClosed()
                  && output.texture().isClosed();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      for (Thread worker : workers) {
        long remaining = deadline - System.nanoTime();
        if (worker.isAlive() && remaining > 0) {
          TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
        }
      }
      workerTerminated = workers.stream().noneMatch(Thread::isAlive) && workerThreads().isEmpty();
      if (!resourcesClosed) throw new AssertionError("CBBG GPU resources survived its close hook");
      if (!workerTerminated)
        throw new AssertionError("CBBG worker survived shutdown for 10 seconds");
      if (generation != null) {
        if (generation.completions != 0) {
          throw new AssertionError("Shutdown job completed before cancellation");
        }
        ReleaseGeneratingShutdownGameTest.assertNoJobOutput();
      }
    } catch (Throwable error) {
      failure = error;
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
    } finally {
      evidence.addProperty("resourcesClosed", resourcesClosed);
      evidence.addProperty("workerTerminated", workerTerminated);
      evidence.addProperty(
          "generationCompleted", generation != null && generation.completions != 0);
      if (failure != null) evidence.addProperty("failure", failure.toString());
      if (generation != null) generation.close();
    }
    try {
      String directory = System.getProperty("cbbg.test.evidence");
      if (directory == null || directory.isBlank()) {
        throw new AssertionError("cbbg.test.evidence is required");
      }
      Path path = Path.of(directory);
      Files.createDirectories(path);
      Files.writeString(path.resolve("shutdown.json"), evidence + "\n");
    } catch (Exception error) {
      throw new AssertionError("Could not record shutdown evidence", error);
    }
    if (failure != null) {
      LoggerFactory.getLogger("cbbg-release-shutdown")
          .error("Shutdown verification failed", failure);
      throw new AssertionError("Shutdown verification failed", failure);
    }
  }
}
