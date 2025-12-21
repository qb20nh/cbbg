package com.qb20nh.cbbg.math;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MiniFFTTest {

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



