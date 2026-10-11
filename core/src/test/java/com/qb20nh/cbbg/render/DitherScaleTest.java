package com.qb20nh.cbbg.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DitherScaleTest {
  @Test
  void fractionalScaleUsesEachRoundedDimension() {
    assertEquals(319f / 960, DitherScale.forDimension(0.3333f, 960));
    assertEquals(179f / 540, DitherScale.forDimension(0.3333f, 540));
    assertEquals(671f / 960, DitherScale.forDimension(0.7f, 960));
    assertEquals(377f / 540, DitherScale.forDimension(0.7f, 540));
    assertEquals(1f / 2, DitherScale.forDimension(0.1f, 2));
  }

  @Test
  void wholePixelsAndUpscalingKeepTheirExistingGrid() {
    assertEquals(0.5f, DitherScale.forDimension(0.5f, 960));
    assertEquals(1f, DitherScale.forDimension(1, 960));
    assertEquals(1f, DitherScale.forDimension(2, 960));
    assertEquals(1f, DitherScale.forDimension(Float.NaN, 960));
    assertEquals(1f, DitherScale.forDimension(0, 960));
  }
}
