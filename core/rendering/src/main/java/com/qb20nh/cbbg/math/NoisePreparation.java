package com.qb20nh.cbbg.math;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** CPU preparation shared by loader startup and renderer initialization. */
@NullMarked
public final class NoisePreparation implements AutoCloseable {
  public static final class Fields {
    private final double[] u;
    private final double[] v;

    private Fields(double[] u, double[] v) {
      this.u = u;
      this.v = v;
    }

    public double[] u() {
      return u;
    }

    public double[] v() {
      return v;
    }
  }

  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "cbbg-stbn");
            thread.setDaemon(true);
            return thread;
          });
  private int width;
  private int height;
  private int depth;
  private long seed;
  private @Nullable CompletableFuture<@Nullable Fields> result;
  private @Nullable Future<?> work;

  public synchronized CompletableFuture<@Nullable Fields> prepare(
      int w, int h, int d, long value, boolean force, BooleanSupplier cacheValid) {
    if (!force && result != null && matches(w, h, d, value) && !result.isCancelled()) {
      return result;
    }
    boolean changedSeed = result != null && seed != value;
    if (work != null) work.cancel(true);
    if (result != null) result.cancel(false);
    width = w;
    height = h;
    depth = d;
    seed = value;
    CompletableFuture<@Nullable Fields> next = new CompletableFuture<>();
    result = next;
    work =
        executor.submit(
            () -> {
              try {
                if (!force && !changedSeed && cacheValid.getAsBoolean()) {
                  next.complete(null);
                  return;
                }
                if (Thread.currentThread().isInterrupted()) return;
                double[] u = BlueNoise.generateScalarField(w, h, d, BlueNoise.stbnUSeed(value));
                if (Thread.currentThread().isInterrupted()) return;
                double[] v = BlueNoise.generateScalarField(w, h, d, BlueNoise.stbnVSeed(value));
                if (!Thread.currentThread().isInterrupted()) next.complete(new Fields(u, v));
              } catch (Throwable failure) {
                next.completeExceptionally(failure);
              }
            });
    return next;
  }

  public synchronized boolean matches(int w, int h, int d, long value) {
    return result != null && width == w && height == h && depth == d && seed == value;
  }

  @Override
  public synchronized void close() {
    if (work != null) work.cancel(true);
    if (result != null) result.cancel(false);
    executor.shutdownNow();
  }
}
