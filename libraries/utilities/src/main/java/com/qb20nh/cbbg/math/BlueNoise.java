package com.qb20nh.cbbg.math;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.NullMarked;

/** Loader-independent STBN math. Pixel values use the existing ABGR byte layout. */
@NullMarked
public final class BlueNoise {
  private BlueNoise() {}

  public static double[] generateScalarField(int w, int h, int d, long seed) {
    checkCancellation();
    return new Workspace(w, h, d).generate(seed);
  }

  /** Generates packed ABGR noise, sharing temporary storage between the two scalar fields. */
  public static int[] generatePixels(int w, int h, int d, long seed) {
    checkCancellation();
    Workspace workspace = new Workspace(w, h, d);
    double[] u = workspace.generate(stbnUSeed(seed));
    checkCancellation();
    double[] v = workspace.generate(stbnVSeed(seed));
    checkCancellation();
    int[] pixels = new int[u.length];
    for (int i = 0; i < pixels.length; i++) {
      pixels[i] = calculatePixelColor(u[i], v[i]);
    }
    checkCancellation();
    return pixels;
  }

  private static void checkCancellation() {
    if (Thread.currentThread().isInterrupted()) {
      throw new CancellationException("Noise generation was interrupted");
    }
  }

  private static final class Workspace {
    private final int w;
    private final int h;
    private final int d;
    private final double[] imag;
    private final double[] filter;
    private final int[] indices;
    private final int[] scratch;

    private Workspace(int w, int h, int d) {
      this.w = w;
      this.h = h;
      this.d = d;
      int size = w * h * d;
      imag = new double[size];
      filter = computeFilterWeights(w, h, d);
      indices = new int[size];
      scratch = new int[size];
    }

    private double[] generate(long seed) {
      double[] real = initializeRandomField(imag.length, seed);
      Arrays.fill(imag, 0.0);
      for (int i = 0; i < indices.length; i++) {
        indices[i] = i;
      }
      for (int iter = 0; iter < 10; iter++) {
        checkCancellation();
        MiniFFT.fft3d(real, imag, w, h, d, false);
        checkCancellation();
        applyFilter(real, imag, filter);
        checkCancellation();
        MiniFFT.fft3d(real, imag, w, h, d, true);
        applyHistogramMatching(real, imag, indices, scratch);
        checkCancellation();
      }
      return real;
    }
  }

  private static double[] initializeRandomField(int size, long seed) {
    double[] real = new double[size];
    Random rng = new Random(seed);
    for (int i = 0; i < size; i++) {
      real[i] = rng.nextDouble();
    }
    return real;
  }

  private static double[] computeDistanceGrid(int w, int h, int d) {
    double[] distSq = new double[w * h * d];
    for (int z = 0; z < d; z++) {
      double kz = (z <= d / 2.0) ? (double) z / d : (double) (z - d) / d;
      for (int y = 0; y < h; y++) {
        double ky = (y <= h / 2.0) ? (double) y / h : (double) (y - h) / h;
        for (int x = 0; x < w; x++) {
          double kx = (x <= w / 2.0) ? (double) x / w : (double) (x - w) / w;
          distSq[(z * h + y) * w + x] = kx * kx + ky * ky + kz * kz;
        }
      }
    }
    return distSq;
  }

  private static double[] computeFilterWeights(int w, int h, int d) {
    double[] filter = computeDistanceGrid(w, h, d);
    double sigma = 0.2;
    double twoSigmaSq = 2 * sigma * sigma;
    for (int i = 0; i < filter.length; i++) {
      filter[i] = 1.0 - Math.exp(-filter[i] / twoSigmaSq);
    }
    return filter;
  }

  private static void applyFilter(double[] real, double[] imag, double[] filter) {
    for (int i = 0; i < real.length; i++) {
      double mag = Math.sqrt(real[i] * real[i] + imag[i] * imag[i]);
      double newMag = mag * filter[i];
      if (mag > 1e-9) {
        real[i] *= newMag / mag;
        imag[i] *= newMag / mag;
      } else {
        real[i] = 0;
        imag[i] = 0;
      }
    }
  }

  private static void applyHistogramMatching(
      double[] real, double[] imag, int[] indices, int[] scratch) {
    sortIndices(real, indices, scratch);
    for (int k = 0; k < real.length; k++) {
      int idx = indices[k];
      real[idx] = (double) k / (real.length - 1);
      imag[idx] = 0;
    }
  }

  static void sortIndices(double[] values, int[] indices, int[] scratch) {
    checkCancellation();
    if (indices.length <= 64) {
      for (int i = 1; i < indices.length; i++) {
        int index = indices[i];
        int j = i;
        while (j > 0 && Double.compare(values[indices[j - 1]], values[index]) > 0) {
          indices[j] = indices[j - 1];
          j--;
        }
        indices[j] = index;
      }
      return;
    }
    int[] from = indices;
    int[] to = scratch;
    int[] offsets = new int[256];
    // Eight stable passes write the result back into indices.
    for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
      checkCancellation();
      Arrays.fill(offsets, 0);
      for (int index : from) offsets[bucket(values[index], shift)]++;
      int total = 0;
      for (int i = 0; i < offsets.length; i++) {
        int count = offsets[i];
        offsets[i] = total;
        total += count;
      }
      for (int index : from) to[offsets[bucket(values[index], shift)]++] = index;
      int[] previous = from;
      from = to;
      to = previous;
    }
  }

  private static int bucket(double value, int shift) {
    // Canonical NaNs and reversed negative encodings preserve Double.compare ordering.
    long bits = Double.doubleToLongBits(value);
    long key = bits ^ ((bits >> 63) | Long.MIN_VALUE);
    return (int) (key >>> shift) & 0xff;
  }

  public static long stbnUSeed(long seed) {
    return seed == 0 ? 1234 : seed * 31;
  }

  public static long stbnVSeed(long seed) {
    return seed == 0 ? 5678 : seed * 31 + 7;
  }

  public static int calculatePixelColor(double u, double v) {
    double zz = 2.0 * u - 1.0;
    double theta = 2.0 * Math.PI * v;
    if (zz > 1.0) zz = 1.0;
    if (zz < -1.0) zz = -1.0;
    double radius = Math.sqrt(1.0 - zz * zz);
    double xx = radius * Math.cos(theta);
    double yy = radius * Math.sin(theta);
    int r = (int) Math.round((xx + 1.0) * 127.5);
    int g = (int) Math.round((yy + 1.0) * 127.5);
    int b = (int) Math.round((zz + 1.0) * 127.5);
    r = Math.max(0, Math.min(r, 255));
    g = Math.max(0, Math.min(g, 255));
    b = Math.max(0, Math.min(b, 255));
    return (0xFF << 24) | (b << 16) | (g << 8) | r;
  }
}
