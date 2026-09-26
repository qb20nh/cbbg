package com.qb20nh.cbbg.math;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MiniFFTTest {

    @Test
    void forwardTransformMatchesIndependentDiscreteFourierReference() {
        double[] input = {0.0, 1.0, -2.5, 3.25, 4.0, -5.0, 6.75, -7.125};
        double[] real = input.clone();
        double[] imag = new double[input.length];
        MiniFFT.fft1d(real, imag, false);
        for (int frequency = 0; frequency < input.length; frequency++) {
            double expectedReal = 0;
            double expectedImag = 0;
            for (int sample = 0; sample < input.length; sample++) {
                double angle = -2 * Math.PI * sample * frequency / input.length;
                expectedReal += input[sample] * Math.cos(angle);
                expectedImag += input[sample] * Math.sin(angle);
            }
            Assertions.assertEquals(expectedReal, real[frequency], 1e-9);
            Assertions.assertEquals(expectedImag, imag[frequency], 1e-9);
        }
    }

    @Test
    void nonCubicThreeDimensionalImpulseHasFlatSpectrum() {
        double[] real = new double[8 * 4 * 2];
        double[] imag = new double[real.length];
        real[0] = 1;
        MiniFFT.fft3d(real, imag, 8, 4, 2, false);
        for (int i = 0; i < real.length; i++) {
            Assertions.assertEquals(1, real[i], 1e-9);
            Assertions.assertEquals(0, imag[i], 1e-9);
        }
        MiniFFT.fft3d(real, imag, 8, 4, 2, true);
        Assertions.assertEquals(1, real[0], 1e-9);
        for (int i = 1; i < real.length; i++) {
            Assertions.assertEquals(0, real[i], 1e-9);
        }
    }

    @Test
    void fft1d_roundTripRestoresOriginalSignal() {
        double[] real = {0.0, 1.0, -2.5, 3.25, 4.0, -5.0, 6.75, -7.125};
        double[] imag = new double[real.length];
        double[] original = real.clone();

        MiniFFT.fft1d(real, imag, false);
        MiniFFT.fft1d(real, imag, true);

        Assertions.assertArrayEquals(original, real, 1e-9);
        for (double v : imag) {
            Assertions.assertEquals(0.0, v, 1e-9);
        }
    }

    @Test
    void fft1d_nonPowerOfTwo_throws() {
        double[] real = new double[6];
        double[] imag = new double[6];
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MiniFFT.fft1d(real, imag, false));
    }
}
