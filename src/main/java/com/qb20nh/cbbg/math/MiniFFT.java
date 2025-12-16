package com.qb20nh.cbbg.math;

/**
 * Minimal Cooley-Tukey FFT implementation for power-of-two sizes.
 */
public class MiniFFT {

    private MiniFFT() {
    }

    /**
     * Computes 1D FFT in-place.
     *
     * @param real    Real parts.
     * @param imag    Imaginary parts.
     * @param inverse If true, compute Inverse FFT.
     */
    public static void fft1d(double[] real, double[] imag, boolean inverse) {
        int n = real.length;
        if (n == 0)
            return;
        if ((n & (n - 1)) != 0)
            throw new IllegalArgumentException("Length must be power of 2");

        bitReversalPermutation(real, imag, n);
        butterflyUpdates(real, imag, n, inverse);
        if (inverse) {
            scaleForInverse(real, imag, n);
        }
    }

    /**
     * Computes 3D FFT in-place.
     * Data is flat: [z * height * width + y * width + x]
     */
    public static void fft3d(double[] real, double[] imag, int w, int h, int d, boolean inverse) {
        processXAxis(real, imag, w, h, d, inverse);
        processYAxis(real, imag, w, h, d, inverse);
        processZAxis(real, imag, w, h, d, inverse);
    }

    private static void bitReversalPermutation(double[] real, double[] imag, int n) {
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (i < j) {
                double tr = real[i];
                real[i] = real[j];
                real[j] = tr;
                double ti = imag[i];
                imag[i] = imag[j];
                imag[j] = ti;
            }
            int m = n >> 1;
            while (j >= m && m > 0) {
                j -= m;
                m >>= 1;
            }
            j += m;
        }
    }

    private static void butterflyUpdates(double[] real, double[] imag, int n, boolean inverse) {
        for (int step = 1; step < n; step <<= 1) {
            double theta = (inverse ? 1.0 : -1.0) * Math.PI / step;
            double wReal = Math.cos(theta);
            double wImag = Math.sin(theta);

            for (int group = 0; group < n; group += (step << 1)) {
                double wr = 1.0;
                double wi = 0.0;
                for (int k = 0; k < step; k++) {
                    int even = group + k;
                    int odd = even + step;

                    double tr = wr * real[odd] - wi * imag[odd];
                    double ti = wr * imag[odd] + wi * real[odd];

                    real[odd] = real[even] - tr;
                    imag[odd] = imag[even] - ti;
                    real[even] += tr;
                    imag[even] += ti;

                    double nextWr = wr * wReal - wi * wImag;
                    double nextWi = wr * wImag + wi * wReal;
                    wr = nextWr;
                    wi = nextWi;
                }
            }
        }
    }

    private static void scaleForInverse(double[] real, double[] imag, int n) {
        for (int i = 0; i < n; i++) {
            real[i] /= n;
            imag[i] /= n;
        }
    }

    private static void processXAxis(double[] real, double[] imag, int w, int h, int d, boolean inverse) {
        double[] rowR = new double[w];
        double[] rowI = new double[w];
        for (int z = 0; z < d; z++) {
            for (int y = 0; y < h; y++) {
                int offset = (z * h + y) * w;
                processLine(real, imag, offset, w, 1, rowR, rowI, inverse);
            }
        }
    }

    private static void processYAxis(double[] real, double[] imag, int w, int h, int d, boolean inverse) {
        double[] colR = new double[h];
        double[] colI = new double[h];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int offset = z * h * w + x;
                processLine(real, imag, offset, h, w, colR, colI, inverse);
            }
        }
    }

    private static void processZAxis(double[] real, double[] imag, int w, int h, int d, boolean inverse) {
        double[] depthR = new double[d];
        double[] depthI = new double[d];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int offset = y * w + x;
                processLine(real, imag, offset, d, w * h, depthR, depthI, inverse);
            }
        }
    }

    private static void processLine(double[] real, double[] imag, int startIdx, int count, int stride,
            double[] bufR, double[] bufI, boolean inverse) {
        for (int i = 0; i < count; i++) {
            int idx = startIdx + i * stride;
            bufR[i] = real[idx];
            bufI[i] = imag[idx];
        }
        fft1d(bufR, bufI, inverse);
        for (int i = 0; i < count; i++) {
            int idx = startIdx + i * stride;
            real[idx] = bufR[i];
            imag[idx] = bufI[i];
        }
    }
}
