package com.qb20nh.cbbg.math;

import java.util.Arrays;
import java.util.Random;

/** Loader-independent STBN math. Pixel values use the existing ABGR byte layout. */
public final class BlueNoise {
    private BlueNoise() {}

    public static double[] generateScalarField(int w, int h, int d, long seed) {
        int size = w * h * d;
        double[] real = initializeRandomField(size, seed);
        double[] imag = new double[size];
        double[] filter = computeFilterWeights(computeDistanceGrid(w, h, d));
        Integer[] indices = new Integer[size];
        for (int i = 0; i < size; i++) {
            indices[i] = i;
        }
        for (int iter = 0; iter < 10; iter++) {
            MiniFFT.fft3d(real, imag, w, h, d, false);
            applyFilter(real, imag, filter);
            MiniFFT.fft3d(real, imag, w, h, d, true);
            applyHistogramMatching(real, imag, indices);
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        return real;
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

    private static double[] computeFilterWeights(double[] distSq) {
        double[] filter = new double[distSq.length];
        double sigma = 0.2;
        double twoSigmaSq = 2 * sigma * sigma;
        for (int i = 0; i < distSq.length; i++) {
            filter[i] = 1.0 - Math.exp(-distSq[i] / twoSigmaSq);
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

    private static void applyHistogramMatching(double[] real, double[] imag, Integer[] indices) {
        Arrays.sort(indices, (a, b) -> Double.compare(real[a], real[b]));
        for (int k = 0; k < real.length; k++) {
            int idx = indices[k];
            real[idx] = (double) k / (real.length - 1);
            imag[idx] = 0;
        }
    }

    public static long stbnUSeed(long seed) { return seed == 0 ? 1234 : seed * 31; }

    public static long stbnVSeed(long seed) { return seed == 0 ? 5678 : seed * 31 + 7; }

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
