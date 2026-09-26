package com.qb20nh.cbbg.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class DitherReferenceTest {
  @Test
  void nonBoundaryChannelsDistinguishMissingAndDoubleApplication() {
    double source = 127.25 / 255;
    int plain = DitherReference.channel(source, 255, 0, 0, 6, false);
    int once = DitherReference.channel(source, 255, 2, 0, 6, false);
    int twice = DitherReference.channel(once / 255.0, 255, 2, 0, 6, false);
    assertEquals(127, plain);
    assertEquals(128, once);
    assertEquals(129, twice);
    assertNotEquals(plain, once);
    assertNotEquals(once, twice);
    assertEquals(126, DitherReference.channel(source, 0, 2, 0, 6, false));
  }

  @Test
  void sourceAndResultAreClampedSeparately() {
    assertEquals(2, DitherReference.channel(-1, 255, 4, 0, 6, false));
    assertEquals(253, DitherReference.channel(2, 0, 4, 0, 6, false));
    assertEquals(0, DitherReference.channel(0, 0, 4, 0, 6, false));
    assertEquals(255, DitherReference.channel(1, 255, 4, 0, 6, false));
  }

  @Test
  void demoUsesFloorMidpointAndInvertsOnlyTheSeparator() {
    for (int width : new int[] {5, 6}) {
      for (int x = 0; x < width; x++) {
        int expected = x < width / 2 ? 127 : x == width / 2 ? 127 : 128;
        assertEquals(expected, DitherReference.channel(127.25 / 255, 255, 2, x, width, true));
      }
      assertEquals(255, DitherReference.channel(0, 0, 4, width / 2, width, true));
    }
  }

  @Test
  void pixelGridScalesBeforeRepeatingTheNoiseTile() {
    int[] expected = {0, 0, 1, 1, 2, 2, 0, 0};
    for (int pixel = 0; pixel < expected.length; pixel++) {
      assertEquals(expected[pixel], DitherReference.noiseCoordinate(pixel, 0.5, 3));
    }
    assertEquals(2, DitherReference.noiseCoordinate(-1, 1, 3));
    assertEquals(3, DitherReference.noiseCoordinate(4, 2, 5));
  }
}
