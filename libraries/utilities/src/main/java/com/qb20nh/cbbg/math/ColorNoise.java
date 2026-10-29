package com.qb20nh.cbbg.math;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.NullMarked;

/** Correlated color noise from two filtered Gaussian fields. */
@NullMarked
final class ColorNoise {
  private ColorNoise() {}

  static int[] generate(int w, int h, int d, long seed) {
    int n = Math.multiplyExact(Math.multiplyExact(w, h), d);
    int[] indices = new int[n], scratch = new int[n];
    double[] phases = phases(w, h, d, seed, indices, scratch);
    for (int i = 0; i < n; i++) indices[i] = i;
    BlueNoise.sortIndices(phases, indices, scratch);
    int[] pixels = new int[n];
    // Uniform circular ranks keep every triangular channel uniform. Their
    // one-third-cycle offsets reduce shared bright/dark quantization errors.
    for (int k = 0; k < n; k++) {
      double phase = (k + .5) / n;
      pixels[indices[k]] =
          0xff000000
              | value(triangle(phase))
              | value(triangle(phase + 1.0 / 3)) << 8
              | value(triangle(phase + 2.0 / 3)) << 16;
    }
    return pixels;
  }

  private static double triangle(double phase) {
    phase -= Math.floor(phase);
    return 2 * Math.abs(phase - .5);
  }

  private static int value(double x) {
    return (int) Math.round(x * 255);
  }

  private static double[] phases(int w, int h, int d, long seed, int[] indices, int[] scratch) {
    int n = w * h * d;
    double[] real = random(n, BlueNoise.stbnUSeed(seed)),
        imag = random(n, BlueNoise.stbnVSeed(seed));
    double[] gaussian = new double[n];
    Random quantileRandom = new Random(seed == 0 ? 9012 : seed * 31 + 13);
    for (int i = 0; i < n / 2; i++) {
      double radius = StrictMath.sqrt(-2 * StrictMath.log(1 - quantileRandom.nextDouble()));
      double sample = Math.abs(radius * StrictMath.cos(2 * Math.PI * quantileRandom.nextDouble()));
      gaussian[i] = -sample;
      gaussian[n - 1 - i] = sample;
    }
    Arrays.sort(gaussian);
    double[] xy = new double[w * h], time = new double[d];
    double sigma = .2, twoSigmaSq = 2 * sigma * sigma;
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        double kx = (x <= w / 2 ? (double) x : x - w) / w,
            ky = (y <= h / 2 ? (double) y : y - h) / h;
        xy[y * w + x] = Math.exp(-(kx * kx + ky * ky) / twoSigmaSq);
      }
    for (int z = 0; z < d; z++) {
      double kz = (z <= d / 2 ? (double) z : z - d) / d;
      time[z] = Math.exp(-kz * kz / twoSigmaSq);
    }
    // A real, even filter processes both real signals in one complex FFT.
    for (int iteration = 0; iteration < 10; iteration++) {
      cancelled();
      MiniFFT.fft3d(real, imag, w, h, d, false);
      for (int z = 0; z < d; z++)
        for (int pixel = 0; pixel < w * h; pixel++) {
          int i = z * w * h + pixel;
          double weight = 1 - xy[pixel] * time[z];
          real[i] *= weight;
          imag[i] *= weight;
        }
      cancelled();
      MiniFFT.fft3d(real, imag, w, h, d, true);
      rank(real, gaussian, indices, scratch);
      rank(imag, gaussian, indices, scratch);
    }
    for (int i = 0; i < n; i++) real[i] = StrictMath.atan2(imag[i], real[i]);
    return real;
  }

  private static void rank(double[] values, double[] gaussian, int[] indices, int[] scratch) {
    for (int i = 0; i < indices.length; i++) indices[i] = i;
    BlueNoise.sortIndices(values, indices, scratch);
    for (int k = 0; k < indices.length; k++) values[indices[k]] = gaussian[k];
  }

  private static double[] random(int n, long seed) {
    double[] values = new double[n];
    Random rng = new Random(seed);
    for (int i = 0; i < n; i++) values[i] = rng.nextDouble();
    return values;
  }

  private static void cancelled() {
    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
  }
}
