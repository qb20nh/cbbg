package com.qb20nh.cbbg.api;

import static org.junit.jupiter.api.Assertions.*;

import com.qb20nh.cbbg.math.BlueNoise;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class UtilitiesTest {
  @Test
  void generatesExistingNoiseAndWrapsCoordinates() {
    NoiseVolume noise = NoiseVolume.generate(4, 2, 2, 42);
    double[] u = BlueNoise.generateScalarField(4, 2, 2, BlueNoise.stbnUSeed(42));
    double[] v = BlueNoise.generateScalarField(4, 2, 2, BlueNoise.stbnVSeed(42));
    for (int i = 0; i < u.length; i++) {
      assertEquals(
          BlueNoise.calculatePixelColor(u[i], v[i]), noise.pixelABGR(i % 4, i / 4 % 2, i / 8));
    }
    assertEquals(noise.pixelABGR(3, 1, 1), noise.pixelABGR(-1, -1, -1));
    byte[] frame = noise.frameRGBA(1);
    int pixel = noise.pixelABGR(0, 0, 1);
    assertEquals(pixel & 255, frame[0] & 255);
    assertEquals(pixel >>> 8 & 255, frame[1] & 255);
    assertEquals(pixel >>> 16 & 255, frame[2] & 255);
    assertEquals(255, frame[3] & 255);
    frame[0] ^= 255;
    assertEquals(pixel & 255, noise.frameRGBA(1)[0] & 255);
  }

  @Test
  void rejectsInvalidNoiseAndCancellation() {
    assertThrows(IllegalArgumentException.class, () -> NoiseVolume.generate(0, 2, 2, 0));
    assertThrows(IllegalArgumentException.class, () -> NoiseVolume.generate(3, 2, 2, 0));
    assertThrows(IllegalArgumentException.class, () -> NoiseVolume.generate(1, 1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> NoiseVolume.generate(1 << 30, 4, 4, 0));
    Thread.currentThread().interrupt();
    try {
      assertThrows(CancellationException.class, () -> NoiseVolume.generate(2, 2, 1, 0));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void dithersRgbAndPreservesAlphaUsingPixelCenters() {
    NoiseVolume noise = NoiseVolume.generate(4, 2, 2, 5);
    float[] source = {0.371f, 0.631f, 0.213f, 0.2f, 0.571f, 0.431f, 0.713f, 0.8f};
    byte[] result = Dithering.rgba8(source, 2, 1, noise, 1, new DitherOptions(2, 0.7f, 1, false));
    for (int x = 0; x < 2; x++) {
      int pixel = noise.pixelABGR((int) Math.floor((x + 0.5) * 0.7f), 0, 1);
      for (int c = 0; c < 3; c++) {
        double offset = ((pixel >>> (c * 8) & 255) / 255.0 - 0.5) * 2;
        assertEquals(
            (int) Math.floor(source[x * 4 + c] * 255.0 + offset + 0.5), result[x * 4 + c] & 255);
      }
    }
    assertEquals(51, result[3] & 255);
    assertEquals(204, result[7] & 255);
  }

  @Test
  void clampsAfterDitheringAndSupportsDemo() {
    NoiseVolume noise = NoiseVolume.generate(2, 2, 1, 0);
    float[] source = {-2, 2, 0.4f, -1, 0.6f, 0.7f, 0.8f, 2};
    byte[] result = Dithering.rgba8(source, 2, 1, noise, 0, new DitherOptions(0, 1, 1, true));
    assertArrayEquals(new byte[] {0, (byte) 255, 102, 0, 102, 77, 51, (byte) 255}, result);
    assertThrows(IllegalArgumentException.class, () -> new DitherOptions(Float.NaN, 1, 1, false));
    assertThrows(IllegalArgumentException.class, () -> new DitherOptions(1, 0, 1, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> Dithering.rgba8(new float[3], 1, 1, noise, 0, new DitherOptions(1, 1, 1, false)));
  }
}
