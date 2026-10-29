package com.qb20nh.cbbg.math;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
class BlueNoiseTest {
  @Test
  void cancelledSortingStopsBeforeChangingRanks() {
    double[] values = new double[128];
    int[] indices = new int[values.length];
    for (int i = 0; i < values.length; i++) {
      values[i] = values.length - i;
      indices[i] = i;
    }
    int[] original = indices.clone();
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          CancellationException.class,
          () -> BlueNoise.sortIndices(values, indices, new int[indices.length]));
      assertTrue(Thread.currentThread().isInterrupted());
      assertArrayEquals(original, indices);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void packedNoiseIsSeededAndUsesOpaqueAbgr() {
    int[][] dimensions = {{2, 1, 1}, {8, 8, 1}, {8, 4, 2}, {16, 8, 4}};
    for (int[] size : dimensions) {
      for (long seed : new long[] {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
        int[] pixels = BlueNoise.generatePixels(size[0], size[1], size[2], seed);
        assertEquals(size[0] * size[1] * size[2], pixels.length);
        assertArrayEquals(pixels, BlueNoise.generatePixels(size[0], size[1], size[2], seed));
        for (int pixel : pixels) assertEquals(255, pixel >>> 24);
      }
    }
    assertFalse(
        Arrays.equals(BlueNoise.generatePixels(8, 4, 2, 1), BlueNoise.generatePixels(8, 4, 2, 2)));
  }

  @Test
  void colorNoiseKeepsUniformChannelsWithoutExtraBrightnessGrain() {
    for (long seed : new long[] {0, 1, 42}) {
      int[] pixels = BlueNoise.generatePixels(32, 32, 16, seed);
      for (int channel = 0; channel < 3; channel++) {
        int[] histogram = new int[256];
        for (int pixel : pixels) histogram[pixel >>> (8 * channel) & 255]++;
        int cumulative = 0;
        for (int value = 0; value < 256; value++) {
          cumulative += histogram[value];
          double expected = Math.min(1, (value + 0.5) / 255);
          assertEquals(expected, (double) cumulative / pixels.length, 2.0 / pixels.length);
        }
      }
      // Luminance at half-step input detects independent RGB noise that has good
      // marginal spectra but adds brightness grain. Both ABGR and PNG order apply.
      for (double[] weights : new double[][] {{0.2126, 0.7152, 0.0722}, {0.0722, 0.7152, 0.2126}}) {
        double squared = 0;
        for (int pixel : pixels) {
          double error = 0;
          for (int channel = 0; channel < 3; channel++) {
            double noise = (pixel >>> (8 * channel) & 255) / 255.0;
            error += weights[channel] * (Math.floor(0.5 + noise) - 0.5);
          }
          squared += error * error;
        }
        assertTrue(Math.sqrt(squared / pixels.length) < 0.33, "Brightness RMS for seed " + seed);
      }
    }
  }

  @Test
  void channelsSuppressNeighborPatternsWithoutAlternatingFrames() {
    for (long seed : new long[] {0, 1, 42}) {
      int[] pixels = BlueNoise.generatePixels(32, 32, 16, seed);
      for (int channel = 0; channel < 3; channel++) {
        assertTrue(
            noiseCorrelation(pixels, channel, 1, 0, 0) < -0.15,
            "Spatial X correlation for channel " + channel + ", seed " + seed);
        assertTrue(
            noiseCorrelation(pixels, channel, 0, 1, 0) < -0.15,
            "Spatial Y correlation for channel " + channel + ", seed " + seed);
        assertTrue(
            noiseCorrelation(pixels, channel, 0, 0, 1) < -0.15,
            "Temporal correlation for channel " + channel + ", seed " + seed);
        assertTrue(
            Math.abs(noiseCorrelation(pixels, channel, 0, 0, 2)) < 0.3,
            "Alternating-frame correlation for channel " + channel + ", seed " + seed);
      }
    }
  }

  private static double noiseCorrelation(int[] pixels, int channel, int dx, int dy, int dt) {
    double mean = 0;
    for (int pixel : pixels) mean += (pixel >>> (8 * channel) & 255) / (double) pixels.length;
    double variance = 0;
    double covariance = 0;
    for (int z = 0; z < 16; z++) {
      for (int y = 0; y < 32; y++) {
        for (int x = 0; x < 32; x++) {
          int index = (z * 32 + y) * 32 + x;
          int next = (((z + dt) % 16 * 32 + (y + dy) % 32) * 32 + (x + dx) % 32);
          double a = (pixels[index] >>> (8 * channel) & 255) - mean;
          double b = (pixels[next] >>> (8 * channel) & 255) - mean;
          variance += a * a;
          covariance += a * b;
        }
      }
    }
    return covariance / variance;
  }

  @Test
  void indexSortingMatchesJavaStableOrdering() {
    Random random = new Random(87);
    double[] special = {
      Double.NaN,
      Double.longBitsToDouble(0x7ff0000000000001L),
      Double.longBitsToDouble(0xfff8000000000001L),
      -0.0,
      0.0,
      Double.MIN_VALUE,
      -Double.MIN_VALUE,
      Double.MAX_VALUE,
      -Double.MAX_VALUE,
      Double.NEGATIVE_INFINITY,
      Double.POSITIVE_INFINITY
    };
    for (int size : new int[] {0, 1, 2, 3, 4, 7, 8, 31, 63, 64, 65, 127, 1024}) {
      double[] values = new double[size];
      Integer[] expected = new Integer[size];
      for (int i = 0; i < size; i++) expected[i] = i;
      Collections.shuffle(Arrays.asList(expected), random);
      int[] actual = Arrays.stream(expected).mapToInt(Integer::intValue).toArray();
      int[] scratch = new int[size];
      for (int round = 0; round < 3; round++) {
        for (int i = 0; i < size; i++) {
          double value = random.nextInt(7) - 3;
          if (round == 1) value = Double.longBitsToDouble(random.nextLong());
          values[i] = i % 4 == 0 ? special[i % special.length] : value;
        }
        Arrays.sort(expected, Comparator.comparingDouble(i -> values[i]));
        BlueNoise.sortIndices(values, actual, scratch);
        assertArrayEquals(Arrays.stream(expected).mapToInt(Integer::intValue).toArray(), actual);
      }
    }
  }

  @Test
  void fieldsMatchThePrevious26_2Artifact() throws Exception {
    // Generated by STBNGenerator in the 21d11b5 release build before extraction.
    long[] seeds = {1234, 5678, 1302, 1309, -31};
    String[] expected = {
      "bfd4a7c79404a9d3c2e5c15dec09e8352bfdf3e25eb7bec112436c0967136e26",
      "af6cc5fdba290f9a14b352e5b730917931f9dfe246fd0230e59d5efe9e59ab9d",
      "8c391f23ca277bb1f0b5a2dce8ef57d3a82bf3c79ae23cdf39f0b608630b677b",
      "bb284163b56c1d502fdc26cd68da0336eadc4cb76fc78a56c62737da7a0bbe42",
      "ddfc0721dac1d6f6d4756c2be6f2642f35c3bf698b02c0f77fefdeb3c64ada99"
    };
    for (int i = 0; i < seeds.length; i++) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (double value : BlueNoise.generateScalarField(8, 4, 2, seeds[i])) {
        digest.update(ByteBuffer.allocate(8).putDouble(value).array());
      }
      StringBuilder hex = new StringBuilder();
      for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
      assertEquals(expected[i], hex.toString(), "seed " + seeds[i]);
    }
  }

  @Test
  void stbnSeedsPreserveExistingCacheIdentity() {
    assertEquals(1234, BlueNoise.stbnUSeed(0));
    assertEquals(5678, BlueNoise.stbnVSeed(0));
    assertEquals(31, BlueNoise.stbnUSeed(1));
    assertEquals(38, BlueNoise.stbnVSeed(1));
    assertEquals(-31, BlueNoise.stbnUSeed(-1));
    assertEquals(-24, BlueNoise.stbnVSeed(-1));
    assertEquals(Long.MAX_VALUE - 30, BlueNoise.stbnUSeed(Long.MAX_VALUE));
    assertEquals(Long.MAX_VALUE - 23, BlueNoise.stbnVSeed(Long.MAX_VALUE));
  }

  @Test
  void decodedPixelsMatchPreExtractionJava21Fixture() throws Exception {
    // Captured from STBNGenerator at a941365, not from the extracted implementation.
    double[] field = BlueNoise.generateScalarField(8, 4, 2, 1234);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (int i = 0; i < field.length; i++) {
      int pixel = BlueNoise.calculatePixelColor(field[i], field[field.length - 1 - i]);
      digest.update(ByteBuffer.allocate(4).putInt(pixel).array());
    }
    StringBuilder hex = new StringBuilder();
    for (byte value : digest.digest()) {
      hex.append(String.format("%02x", value & 0xff));
    }
    assertEquals(
        "d7d43bf86a2c8a2635b0e23462b5a60f040f5a05c41500a32e7200e59729cf75", hex.toString());
  }

  @Test
  void scalarFieldHasUniformRanksAndIsSeeded() {
    double[] first = BlueNoise.generateScalarField(8, 4, 2, 1234);
    assertArrayEquals(first, BlueNoise.generateScalarField(8, 4, 2, 1234));
    assertFalse(Arrays.equals(first, BlueNoise.generateScalarField(8, 4, 2, 5678)));
    double[] sorted = first.clone();
    Arrays.sort(sorted);
    for (int i = 0; i < sorted.length; i++) {
      assertEquals((double) i / (sorted.length - 1), sorted[i]);
    }
  }

  @Test
  void sphericalMappingPreservesAbgrChannelsAndClampsPoles() {
    assertEquals(0xff008080, BlueNoise.calculatePixelColor(0, 0));
    assertEquals(0xffff8080, BlueNoise.calculatePixelColor(1, 0));
    assertEquals(0xff8080ff, BlueNoise.calculatePixelColor(0.5, 0));
    assertEquals(0xff008080, BlueNoise.calculatePixelColor(-100, 0));
    assertEquals(0xffff8080, BlueNoise.calculatePixelColor(100, 0));
    assertEquals(0xff000000, BlueNoise.calculatePixelColor(Double.NaN, Double.NaN));
  }
}
