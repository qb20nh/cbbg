package com.qb20nh.cbbg.math;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.NullMarked;

/** RGB blue noise with uniform channels and a spherical color distribution. */
@NullMarked
final class ColorNoise {
  private ColorNoise() {}

  static int[] generate(int w, int h, int d, long seed) {
    double[][] channels = filtered(w, h, d, seed);
    int n = channels[0].length;
    int[] indices = new int[n], scratch = new int[n];
    for (double[] channel : channels) rank(channel, indices, scratch);
    int[] pixels = new int[n];
    for (int i = 0; i < n; i++) {
      pixels[i] =
          0xff000000
              | (int) Math.round(channels[0][i] * 255)
              | (int) Math.round(channels[1][i] * 255) << 8
              | (int) Math.round(channels[2][i] * 255) << 16;
    }
    return pixels;
  }

  private static double[][] filtered(int w, int h, int d, long seed) {
    int n = Math.multiplyExact(Math.multiplyExact(w, h), d);
    double[] x = gaussian(n, BlueNoise.stbnUSeed(seed));
    double[] y = gaussian(n, BlueNoise.stbnVSeed(seed));
    double[] z = gaussian(n, seed == 0 ? 9012 : seed * 31 + 13);
    double[] temp = new double[n], xy = new double[w * h], time = new double[d];
    for (int j = 0; j < h; j++)
      for (int i = 0; i < w; i++) {
        double kx = (i <= w / 2 ? (double) i : i - w) / w;
        double ky = (j <= h / 2 ? (double) j : j - h) / h;
        xy[j * w + i] = Math.exp(-(kx * kx + ky * ky) / .08);
      }
    for (int k = 0; k < d; k++) {
      double kz = (k <= d / 2 ? (double) k : k - d) / d;
      time[k] = Math.exp(-kz * kz / .08);
    }
    // The same filter on all components preserves rotational symmetry.
    // Projection restores the sphere after filtering, without favoring a color.
    for (int iteration = 0; iteration < 16 && n > 1; iteration++) {
      filter(x, y, xy, time, w, h, d);
      Arrays.fill(temp, 0);
      filter(z, temp, xy, time, w, h, d);
      for (int i = 0; i < n; i++) {
        double radius = StrictMath.sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i]);
        x[i] /= radius;
        y[i] /= radius;
        z[i] /= radius;
      }
    }
    return new double[][] {x, y, z};
  }

  private static void rank(double[] field, int[] indices, int[] scratch) {
    for (int i = 0; i < indices.length; i++) indices[i] = i;
    BlueNoise.sortIndices(field, indices, scratch);
    for (int i = 0; i < indices.length; i++) field[indices[i]] = (i + .5) / indices.length;
  }

  private static double[] gaussian(int n, long seed) {
    double[] result = new double[n];
    Random rng = new Random(seed);
    for (int i = 0; i < n; i++) result[i] = rng.nextGaussian();
    return result;
  }

  private static void filter(
      double[] real, double[] imag, double[] xy, double[] time, int w, int h, int d) {
    cancelled();
    MiniFFT.fft3d(real, imag, w, h, d, false);
    cancelled();
    for (int k = 0; k < d; k++)
      for (int p = 0; p < w * h; p++) {
        int i = k * w * h + p;
        double weight = 1 - xy[p] * time[k];
        real[i] *= weight;
        imag[i] *= weight;
      }
    MiniFFT.fft3d(real, imag, w, h, d, true);
    cancelled();
  }

  private static void cancelled() {
    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
  }
}
