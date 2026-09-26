package com.qb20nh.cbbg.render;

import static org.junit.jupiter.api.Assertions.*;

import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FormatPolicyTest {
  @Test
  void fullCapabilityMatrixPreservesRequestedPrecisionOrFallsBack() {
    for (boolean half : new boolean[] {false, true}) {
      for (boolean full : new boolean[] {false, true}) {
        java.util.function.Predicate<PixelFormat> supported =
            format -> format == PixelFormat.RGBA16F ? half : full;
        assertEquals(PixelFormat.RGBA8, FormatPolicy.effective(null, supported));
        assertEquals(PixelFormat.RGBA8, FormatPolicy.effective(PixelFormat.RGBA8, supported));
        assertEquals(
            half ? PixelFormat.RGBA16F : PixelFormat.RGBA8,
            FormatPolicy.effective(PixelFormat.RGBA16F, supported));
        assertEquals(
            full ? PixelFormat.RGBA32F : half ? PixelFormat.RGBA16F : PixelFormat.RGBA8,
            FormatPolicy.effective(PixelFormat.RGBA32F, supported));
      }
    }
  }

  @Test
  void probesOnlyNeededFormatsInDescendingOrder() {
    List<PixelFormat> probes = new ArrayList<>();
    FormatPolicy.effective(
        null,
        format -> {
          probes.add(format);
          return false;
        });
    FormatPolicy.effective(
        PixelFormat.RGBA8,
        format -> {
          probes.add(format);
          return false;
        });
    assertTrue(probes.isEmpty());
    FormatPolicy.effective(
        PixelFormat.RGBA32F,
        format -> {
          probes.add(format);
          return false;
        });
    assertEquals(Arrays.asList(PixelFormat.RGBA32F, PixelFormat.RGBA16F), probes);
    probes.clear();
    FormatPolicy.effective(
        PixelFormat.RGBA32F,
        format -> {
          probes.add(format);
          return true;
        });
    assertEquals(Arrays.asList(PixelFormat.RGBA32F), probes);
  }
}
