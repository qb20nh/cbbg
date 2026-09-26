package com.qb20nh.cbbg.render.stbn;

import com.qb20nh.cbbg.math.BlueNoise;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NullMarked
public class STBNGenerator {

  private STBNGenerator() {}

  private static final Logger LOGGER = LoggerFactory.getLogger("cbbg-gen");

  // Pure data container
  public static final class STBNFields {
    private final double[] uField;
    private final double[] vField;
    private final long seed;

    public STBNFields(double[] uField, double[] vField, long seed) {
      this.uField = uField;
      this.vField = vField;
      this.seed = seed;
    }

    public STBNFields(double[] uField, double[] vField) {
      this(uField, vField, 0L);
    }

    public double[] uField() {
      return uField;
    }

    public double[] vField() {
      return vField;
    }

    public long seed() {
      return seed;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) return true;
      if (!(o instanceof STBNFields that)) return false;
      return seed == that.seed
          && Arrays.equals(uField, that.uField)
          && Arrays.equals(vField, that.vField);
    }

    @Override
    public int hashCode() {
      int result = Arrays.hashCode(uField);
      result = 31 * result + Arrays.hashCode(vField);
      return result + Long.hashCode(seed);
    }

    @Override
    public String toString() {
      return "STBNFields{"
          + "uField="
          + Arrays.toString(uField)
          + ", vField="
          + Arrays.toString(vField)
          + '}';
    }
  }

  private static final AtomicReference<@Nullable CompletableFuture<@Nullable STBNFields>>
      pendingFuture = new AtomicReference<>();

  private record Key(int width, int height, int depth, long seed) {}

  private static @Nullable Key earlyKey;
  private static @Nullable CompletableFuture<@Nullable STBNFields> earlyFuture;
  private static final ExecutorService WORKER =
      Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "cbbg-stbn");
            thread.setDaemon(true);
            return thread;
          });

  /**
   * Starts the CPU work during Fabric adapter construction. The first matching request reuses it.
   */
  public static CompletableFuture<@Nullable STBNFields> prepareEarly(
      int w, int h, int d, long seed) {
    return prepareEarly(w, h, d, seed, () -> STBNCache.isCacheValid(w, h, d, seed));
  }

  static synchronized CompletableFuture<@Nullable STBNFields> prepareEarly(
      int w, int h, int d, long seed, BooleanSupplier cacheValid) {
    CompletableFuture<@Nullable STBNFields> prev = pendingFuture.get();
    if (prev != null && !prev.isDone()) prev.cancel(true);
    earlyKey = new Key(w, h, d, seed);
    earlyFuture = submitJob(w, h, d, seed, cacheValid);
    pendingFuture.set(earlyFuture);
    return earlyFuture;
  }

  public static CompletableFuture<@Nullable STBNFields> generateAsync(
      int w, int h, int d, long seed) {
    return generateAsync(w, h, d, seed, () -> STBNCache.isCacheValid(w, h, d, seed));
  }

  // Future identity distinguishes the current request from a replaced request.
  @SuppressWarnings("ReferenceEquality")
  static synchronized CompletableFuture<@Nullable STBNFields> generateAsync(
      int w, int h, int d, long seed, BooleanSupplier cacheValid) {
    CompletableFuture<@Nullable STBNFields> early = earlyFuture;
    Key key = earlyKey;
    earlyFuture = null;
    earlyKey = null;
    if (early != null
        && key != null
        && key.equals(new Key(w, h, d, seed))
        && !early.isCancelled()
        && !early.isCompletedExceptionally()) {
      CompletableFuture<@Nullable STBNFields> claimed = new CompletableFuture<>();
      AtomicReference<CompletableFuture<@Nullable STBNFields>> active =
          new AtomicReference<>(early);
      pendingFuture.set(claimed);
      claimed
          .whenComplete(
              (fields, failure) -> {
                if (claimed.isCancelled()) active.get().cancel(true);
              })
          .exceptionally(STBNGenerator::logObserverFailure);
      early
          .whenComplete(
              (fields, failure) -> {
                if (claimed.isCancelled()) return;
                if (failure != null || fields != null) {
                  complete(claimed, fields, failure);
                  return;
                }
                // A cache hit yielded no fields. Recheck on the worker in case the cache
                // was cleared or damaged while startup work was waiting to be claimed.
                synchronized (STBNGenerator.class) {
                  if (claimed.isCancelled() || pendingFuture.get() != claimed) return;
                  CompletableFuture<@Nullable STBNFields> retry =
                      submitJob(w, h, d, seed, cacheValid);
                  active.set(retry);
                  if (claimed.isCancelled()) retry.cancel(true);
                  retry
                      .whenComplete((value, error) -> complete(claimed, value, error))
                      .exceptionally(STBNGenerator::logObserverFailure);
                }
              })
          .exceptionally(STBNGenerator::logObserverFailure);
      return claimed;
    }
    CompletableFuture<@Nullable STBNFields> prev = pendingFuture.get();
    if (prev != null && !prev.isDone()) prev.cancel(true);
    CompletableFuture<@Nullable STBNFields> next = submitJob(w, h, d, seed, cacheValid);
    pendingFuture.set(next);
    return next;
  }

  private static void complete(
      CompletableFuture<@Nullable STBNFields> future,
      @Nullable STBNFields fields,
      @Nullable Throwable failure) {
    if (failure instanceof CancellationException) future.cancel(false);
    else if (failure != null) future.completeExceptionally(failure);
    else future.complete(fields);
  }

  private static CompletableFuture<@Nullable STBNFields> submitJob(
      int w, int h, int d, long seed, BooleanSupplier cacheValid) {
    CompletableFuture<@Nullable STBNFields> future = new CompletableFuture<>();
    FutureTask<@Nullable STBNFields> task =
        new FutureTask<>(
            (Callable<@Nullable STBNFields>)
                () -> {
                  try {
                    if (Thread.currentThread().isInterrupted()) {
                      return null;
                    }

                    if (cacheValid.getAsBoolean()) {
                      LOGGER.info(
                          "Valid STBN cache found for {}x{}x{}. Skipping math generation.",
                          w,
                          h,
                          d);
                      return null;
                    }

                    LOGGER.info("Starting Async STBN Math Generation ({}x{}x{})...", w, h, d);
                    long start = System.currentTimeMillis();

                    // Generate U and V fields (Spatio-Temporal Blue Noise)
                    // If seed is 0, use existing constants, otherwise mix.
                    long seedU = BlueNoise.stbnUSeed(seed);
                    long seedV = BlueNoise.stbnVSeed(seed);

                    double[] uField = BlueNoise.generateScalarField(w, h, d, seedU);
                    if (Thread.currentThread().isInterrupted()) return null;

                    double[] vField = BlueNoise.generateScalarField(w, h, d, seedV);
                    if (Thread.currentThread().isInterrupted()) return null;

                    long dt = System.currentTimeMillis() - start;
                    LOGGER.info("STBN Math Complete in {} ms", dt);

                    return new STBNFields(uField, vField, seed);
                  } catch (Exception e) {
                    // If interrupted, just return null silently
                    if (e instanceof InterruptedException) return null;
                    throw new CompletionException(e);
                  }
                }) {
          @Override
          protected void done() {
            try {
              future.complete(get());
            } catch (CancellationException cancelled) {
              future.cancel(false);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              future.completeExceptionally(interrupted);
            } catch (ExecutionException failed) {
              Throwable cause = failed.getCause();
              future.completeExceptionally(cause == null ? failed : cause);
            }
          }
        };
    // CompletableFuture.cancel alone does not interrupt its supplier. Link
    // cancellation to the actual task; serialize work until it cooperates.
    future
        .whenComplete(
            (fields, failure) -> {
              if (future.isCancelled()) task.cancel(true);
            })
        .exceptionally(STBNGenerator::logObserverFailure);

    WORKER.execute(task);
    return future;
  }

  private static @Nullable STBNFields logObserverFailure(Throwable failure) {
    Throwable cause =
        failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;
    if (!(cause instanceof CancellationException)) {
      LOGGER.error("STBN generation or completion callback failed", cause);
    }
    return null;
  }

  public static @Nullable CompletableFuture<@Nullable STBNFields> get() {
    return pendingFuture.get();
  }

  public static synchronized void shutdown() {
    earlyKey = null;
    earlyFuture = null;
    CompletableFuture<@Nullable STBNFields> pending = pendingFuture.getAndSet(null);
    if (pending != null) pending.cancel(true);
    WORKER.shutdownNow();
  }

  public static int calculatePixelColor(double u, double v) {
    return BlueNoise.calculatePixelColor(u, v);
  }
}
