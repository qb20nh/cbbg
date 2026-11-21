package com.qb20nh.cbbg.math;

import java.util.Random;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@NullMarked
class MiniFFTTest {

  @Test
  void threeDimensionalAxesMatchDirectTransformsExactly() {
    Random random = new Random(87L);
    for (int size : new int[] {1, 2, 4, 8, 16, 64, 128, 256, 1024, 65536}) {
      double[] real = new double[size];
      double[] imag = new double[size];
      for (int i = 0; i < size; i++) {
        real[i] = random.nextDouble() - 0.5;
        imag[i] = random.nextDouble() - 0.5;
      }
      for (boolean inverse : new boolean[] {false, true}) {
        double[] expectedReal = real.clone();
        double[] expectedImag = imag.clone();
        MiniFFT.fft1d(expectedReal, expectedImag, inverse);
        for (int[] dimensions : new int[][] {{size, 1, 1}, {1, size, 1}, {1, 1, size}}) {
          double[] actualReal = real.clone();
          double[] actualImag = imag.clone();
          MiniFFT.fft3d(
              actualReal, actualImag, dimensions[0], dimensions[1], dimensions[2], inverse);
          Assertions.assertArrayEquals(expectedReal, actualReal);
          Assertions.assertArrayEquals(expectedImag, actualImag);
        }
      }
    }
  }

  @Test
  void forwardTransformMatchesIndependentDiscreteFourierReference() {
    Random random = new Random(513L);
    for (int size : new int[] {1, 2, 4, 8, 16, 64, 128, 256, 512}) {
      double[] inputReal = new double[size];
      double[] inputImag = new double[size];
      for (int i = 0; i < size; i++) {
        inputReal[i] = random.nextDouble() - 0.5;
        inputImag[i] = random.nextDouble() - 0.5;
      }
      for (boolean inverse : new boolean[] {false, true}) {
        double[] real = inputReal.clone();
        double[] imag = inputImag.clone();
        MiniFFT.fft1d(real, imag, inverse);
        for (int frequency = 0; frequency < size; frequency++) {
          double expectedReal = 0;
          double expectedImag = 0;
          for (int sample = 0; sample < size; sample++) {
            double angle = (inverse ? 2 : -2) * Math.PI * sample * frequency / size;
            expectedReal +=
                inputReal[sample] * Math.cos(angle) - inputImag[sample] * Math.sin(angle);
            expectedImag +=
                inputReal[sample] * Math.sin(angle) + inputImag[sample] * Math.cos(angle);
          }
          Assertions.assertEquals(
              inverse ? expectedReal / size : expectedReal, real[frequency], 1e-9);
          Assertions.assertEquals(
              inverse ? expectedImag / size : expectedImag, imag[frequency], 1e-9);
        }
      }
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
    Assertions.assertThrows(IllegalArgumentException.class, () -> MiniFFT.fft1d(real, imag, false));
  }
}
