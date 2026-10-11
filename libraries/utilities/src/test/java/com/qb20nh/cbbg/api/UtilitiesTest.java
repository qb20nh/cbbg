package com.qb20nh.cbbg.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class UtilitiesTest {
  @Test
  void generatesExistingNoiseAndWrapsCoordinates() {
    NoiseVolume noise = NoiseVolume.generate(4, 2, 2, 42);
    // ABGR fixture captured from the original raw scalar-field calculation, in frame/y/x order.
    int[] expected = {
      0xff99ec41, 0xff1150aa, 0xff8807a7, 0xff447ff0,
      0xffdd6d2b, 0xffaab0ed, 0xff774cf4, 0xff008080,
      0xff223454, 0xffee8d41, 0xff55391e, 0xffff8080,
      0xff331a75, 0xff66c91a, 0xffbb80f0, 0xffcce575
    };
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], noise.pixelABGR(i % 4, i / 4 % 2, i / 8));
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
  void keepsTheDemoDividerOnTheMiddlePixelForOddWidths() {
    NoiseVolume noise = NoiseVolume.generate(2, 2, 1, 0);
    for (int width : new int[] {1, 3, 5}) {
      float[] source = new float[width * 4];
      Arrays.fill(source, 0.5f);
      byte[] full = Dithering.rgba8(source, width, 1, noise, 0, new DitherOptions(32, 1, 1, false));
      byte[] demo = Dithering.rgba8(source, width, 1, noise, 0, new DitherOptions(32, 1, 1, true));
      int middle = width / 2;
      for (int channel = 0; channel < 3; channel++) {
        assertEquals(255 - (full[middle * 4 + channel] & 255), demo[middle * 4 + channel] & 255);
        if (width > 1) {
          assertEquals(128, demo[(middle - 1) * 4 + channel] & 255);
          assertEquals(full[(width - 1) * 4 + channel], demo[(width - 1) * 4 + channel]);
        }
      }
      assertEquals(128, demo[middle * 4 + 3] & 255);
    }
  }

  @Test
  void clampsRgbBeforeAddingNoise() {
    NoiseVolume noise = NoiseVolume.generate(2, 2, 1, 0);
    DitherOptions options = new DitherOptions(4, 1, 1, false);
    float[] source = new float[16];
    float[] clamped = new float[16];
    for (float value : new float[] {-0.002f, 1.002f}) {
      Arrays.fill(source, value);
      Arrays.fill(clamped, value < 0 ? 0 : 1);
      for (int i = 3; i < source.length; i += 4) {
        source[i] = clamped[i] = 0.5f;
      }
      byte[] expected = Dithering.rgba8(clamped, 2, 2, noise, 0, options);
      byte[] actual = Dithering.rgba8(source, 2, 2, noise, 0, options);
      assertArrayEquals(expected, actual);
      for (int i = 3; i < actual.length; i += 4) assertEquals(128, actual[i] & 255);
    }
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
