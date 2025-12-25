package com.qb20nh.cbbg.render.stbn;

import com.qb20nh.cbbg.math.MiniFFT;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class STBNGenerator {

    private STBNGenerator() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("cbbg-gen");

    // Pure data container
    public record STBNFields(int width, int height, int depth, long seed, double[] uField,
            double[] vField) {
        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            STBNFields that = (STBNFields) o;
            return width == that.width && height == that.height && depth == that.depth
                    && seed == that.seed && Arrays.equals(uField, that.uField)
                    && Arrays.equals(vField, that.vField);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(width, height, depth, seed);
            result = 31 * result + Arrays.hashCode(uField);
            result = 31 * result + Arrays.hashCode(vField);
            return result;
        }

        @Override
        public String toString() {
            return "STBNFields{" + width + "x" + height + "x" + depth + " seed=" + seed + " uLen="
                    + (uField == null ? -1 : uField.length) + " vLen="
                    + (vField == null ? -1 : vField.length) + '}';
        }
    }

    private static final AtomicReference<CompletableFuture<STBNFields>> pendingFuture =
            new AtomicReference<>();

    public static CompletableFuture<STBNFields> generateAsync(int w, int h, int d, long seed) {
        // Cancel previous if running?
        // Actually, we can just replace the reference. The old one will eventually finish or be
        // GC'd.
        // But we should try to not waste CPU.
        CompletableFuture<STBNFields> prev = pendingFuture.get();
        if (prev != null && !prev.isDone()) {
            prev.cancel(true);
        }

        CompletableFuture<STBNFields> future = CompletableFuture.supplyAsync(() -> {
            try {
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }

                if (STBNCache.isCacheValid(w, h, d)) {
                    LOGGER.info("Valid STBN cache found for {}x{}x{}. Skipping math generation.", w,
                            h, d);
                    return null;
                }

                LOGGER.info("Starting Async STBN Math Generation ({}x{}x{})...", w, h, d);
                long start = System.currentTimeMillis();

                // Generate U and V fields (Spatio-Temporal Blue Noise)
                // If seed is 0, use existing constants, otherwise mix.
                long seedU = seed == 0 ? 1234 : seed * 31;
                long seedV = seed == 0 ? 5678 : seed * 31 + 7;

                double[] uField = generateScalarField(w, h, d, seedU);
                if (Thread.currentThread().isInterrupted())
                    return null;

                double[] vField = generateScalarField(w, h, d, seedV);
                if (Thread.currentThread().isInterrupted())
                    return null;

                long dt = System.currentTimeMillis() - start;
                LOGGER.info("STBN Math Complete in {} ms", dt);

                return new STBNFields(w, h, d, seed, uField, vField);
            } catch (Exception e) {
                // If interrupted, just return null silently
                if (e instanceof InterruptedException)
                    return null;
                throw new CompletionException(e);
            }
        });

        pendingFuture.set(future);
        return future;
    }

    public static CompletableFuture<STBNFields> get() {
        return pendingFuture.get();
    }

    private static double[] generateScalarField(int w, int h, int d, long seed) {
        int size = w * h * d;
        double[] real = initializeRandomField(size, seed);
        double[] imag = new double[size];

        double[] distSq = computeDistanceGrid(w, h, d);
        double[] filter = computeFilterWeights(distSq);

        Integer[] indices = new Integer[size];
        for (int i = 0; i < size; i++) {
            indices[i] = i;
        }

        int iterations = 10;
        for (int iter = 0; iter < iterations; iter++) {
            MiniFFT.fft3d(real, imag, w, h, d, false);
            applyFilter(real, imag, filter);
            MiniFFT.fft3d(real, imag, w, h, d, true);
            applyHistogramMatching(real, imag, indices);

            // Check interruption periodically
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
        int size = w * h * d;
        double[] distSq = new double[size];

        for (int z = 0; z < d; z++) {
            double kz = (z <= d / 2.0) ? (double) z / d : (double) (z - d) / d;
            for (int y = 0; y < h; y++) {
                double ky = (y <= h / 2.0) ? (double) y / h : (double) (y - h) / h;
                for (int x = 0; x < w; x++) {
                    double kx = (x <= w / 2.0) ? (double) x / w : (double) (x - w) / w;
                    int idx = (z * h + y) * w + x;
                    distSq[idx] = kx * kx + ky * ky + kz * kz;
                }
            }
        }
        return distSq;
    }

    private static double[] computeFilterWeights(double[] distSq) {
        int size = distSq.length;
        double[] filter = new double[size];
        double sigma = 0.2; // Aggressive Filter
        double twoSigmaSq = 2 * sigma * sigma;

        for (int i = 0; i < size; i++) {
            filter[i] = 1.0 - Math.exp(-distSq[i] / twoSigmaSq);
        }
        return filter;
    }

    private static void applyFilter(double[] real, double[] imag, double[] filter) {
        int size = real.length;
        for (int i = 0; i < size; i++) {
            double mag = Math.sqrt(real[i] * real[i] + imag[i] * imag[i]);
            double newMag = mag * filter[i];

            if (mag > 1e-9) {
                real[i] *= (newMag / mag);
                imag[i] *= (newMag / mag);
            } else {
                real[i] = 0;
                imag[i] = 0;
            }
        }
    }

    private static void applyHistogramMatching(double[] real, double[] imag, Integer[] indices) {
        int size = real.length;
        Arrays.sort(indices, (a, b) -> Double.compare(real[a], real[b]));

        for (int k = 0; k < size; k++) {
            int idx = indices[k];
            real[idx] = (double) k / (size - 1);
            imag[idx] = 0;
        }
    }

    public static int calculatePixelColor(double u, double v) {
        // Spherical Mapping
        double zz = 2.0 * u - 1.0;
        double theta = 2.0 * Math.PI * v;

        // Clamp z for safety
        if (zz > 1.0)
            zz = 1.0;
        if (zz < -1.0)
            zz = -1.0;

        double radius = Math.sqrt(1.0 - zz * zz);
        double xx = radius * Math.cos(theta);
        double yy = radius * Math.sin(theta);

        // Quantize to 0..255
        int r = (int) Math.round((xx + 1.0) * 127.5);
        int g = (int) Math.round((yy + 1.0) * 127.5);
        int b = (int) Math.round((zz + 1.0) * 127.5);

        // Clamp
        r = Math.clamp(r, 0, 255);
        g = Math.clamp(g, 0, 255);
        b = Math.clamp(b, 0, 255);

        // Clean Alpha (255)
        return (0xFF << 24) | (b << 16) | (g << 8) | r;
    }
}
