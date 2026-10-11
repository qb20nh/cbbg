package com.qb20nh.cbbg.render.stbn;

import com.qb20nh.cbbg.api.NoiseVolume;
import com.qb20nh.cbbg.math.NoisePreparation;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NullMarked
public class STBNGenerator {
  private STBNGenerator() {}

  private static final Logger LOGGER = LoggerFactory.getLogger("cbbg-gen");
  private static final NoisePreparation preparation = new NoisePreparation();
  private static @Nullable CompletableFuture<NoisePreparation.@Nullable Fields> preparationFuture;
  private static @Nullable CompletableFuture<@Nullable STBNFields> pendingFuture;

  public static final class STBNFields {
    private final NoiseVolume volume;

    public STBNFields(NoiseVolume volume) {
      this.volume = volume;
    }

    public NoiseVolume volume() {
      return volume;
    }
  }

  public static CompletableFuture<@Nullable STBNFields> generateAsync(
      int w, int h, int d, long seed) {
    return generateAsync(w, h, d, seed, false);
  }

  // Future identity distinguishes a reused preparation request from a replacement.
  @SuppressWarnings("ReferenceEquality")
  public static synchronized CompletableFuture<@Nullable STBNFields> generateAsync(
      int w, int h, int d, long seed, boolean force) {
    long start = System.nanoTime();
    CompletableFuture<NoisePreparation.@Nullable Fields> next =
        preparation.prepare(
            w,
            h,
            d,
            seed,
            force,
            () -> {
              LOGGER.info("Checking STBN cache ({}x{}x{})", w, h, d);
              return STBNCache.isCacheValid(w, h, d, seed);
            });
    if (next != preparationFuture) {
      if (pendingFuture != null) pendingFuture.cancel(false);
      preparationFuture = next;
      pendingFuture =
          next.thenApply(
              fields -> {
                LOGGER.info(
                    "STBN preparation complete in {} ms ({})",
                    (System.nanoTime() - start) / 1_000_000,
                    fields == null ? "cache" : "generated");
                return fields == null ? null : new STBNFields(fields.volume());
              });
    }
    return java.util.Objects.requireNonNull(pendingFuture);
  }

  public static synchronized @Nullable CompletableFuture<@Nullable STBNFields> get() {
    return pendingFuture;
  }

  public static synchronized void shutdown() {
    if (pendingFuture != null) pendingFuture.cancel(false);
    preparation.close();
    preparationFuture = null;
    pendingFuture = null;
  }

  public static boolean matches(int w, int h, int d, long seed) {
    return preparation.matches(w, h, d, seed);
  }
}
