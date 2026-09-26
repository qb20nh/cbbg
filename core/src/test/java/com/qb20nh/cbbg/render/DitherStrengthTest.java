package com.qb20nh.cbbg.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
class DitherStrengthTest {
  @Test
  void gameplayAndUnblurredScreensKeepTheConfiguredStrength() {
    for (float base : new float[] {0, 0.25f, 0.5f, 1, 2, 4}) {
      assertEquals(base, DitherStrength.effective(base, false, 10));
      assertEquals(base, DitherStrength.effective(base, true, 0));
    }
  }

  @Test
  void menuStrengthScalesWithBlurRadius() {
    assertEquals(1.2f, DitherStrength.effective(1, true, 1));
    assertEquals(2, DitherStrength.effective(1, true, 5));
    assertEquals(3, DitherStrength.effective(1, true, 10));
    assertEquals(1, DitherStrength.effective(0.5f, true, 5));
  }

  @Test
  void menuCompensationRetainsTheOriginalBounds() {
    assertEquals(0.5f, DitherStrength.effective(0, true, 5));
    assertEquals(0.5f, DitherStrength.effective(0.1f, true, 5));
    assertEquals(4, DitherStrength.effective(4, true, 10));
  }
}
