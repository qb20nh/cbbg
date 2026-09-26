package com.qb20nh.cbbg.render.stbn;

import com.qb20nh.cbbg.math.BlueNoise;
import com.qb20nh.cbbg.math.NoisePreparation;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class STBNGenerator {
  private STBNGenerator() {}

  private static final Logger LOGGER = LoggerFactory.getLogger("cbbg-gen");
  private static final NoisePreparation preparation = new NoisePreparation();
  private static CompletableFuture<NoisePreparation.Fields> preparationFuture;
  private static CompletableFuture<STBNFields> pendingFuture;

  public record STBNFields(double[] uField, double[] vField) {
    @Override
    public boolean equals(Object o) {
      return this == o
          || o instanceof STBNFields that
              && Arrays.equals(uField, that.uField)
              && Arrays.equals(vField, that.vField);
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(uField) + Arrays.hashCode(vField);
    }

    @Override
    public String toString() {
      return "STBNFields{uField="
          + Arrays.toString(uField)
          + ", vField="
          + Arrays.toString(vField)
          + '}';
    }
  }

  public static CompletableFuture<STBNFields> generateAsync(int w, int h, int d, long seed) {
    return generateAsync(w, h, d, seed, false);
  }

  public static synchronized CompletableFuture<STBNFields> generateAsync(
      int w, int h, int d, long seed, boolean force) {
    long start = System.nanoTime();
    CompletableFuture<NoisePreparation.Fields> next =
        preparation.prepare(
            w,
            h,
            d,
            seed,
            force,
            () -> {
              LOGGER.info("Checking STBN cache ({}x{}x{})", w, h, d);
              return STBNCache.isCacheValid(w, h, d);
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
                return fields == null ? null : new STBNFields(fields.u(), fields.v());
              });
    }
    return pendingFuture;
  }

  public static synchronized CompletableFuture<STBNFields> get() {
    return pendingFuture;
  }

  public static boolean matches(int w, int h, int d, long seed) {
    return preparation.matches(w, h, d, seed);
  }

  public static int calculatePixelColor(double u, double v) {
    return BlueNoise.calculatePixelColor(u, v);
  }
}
