package com.qb20nh.cbbg.render.stbn;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class STBNGeneratorTest {

    @Test
    void calculatePixelColor_alwaysReturnsOpaqueAndClampedChannels() {
        double[][] cases = {{0.0, 0.0}, {1.0, 1.0}, {-100.0, 100.0}, {0.5, -123.456},
                {Double.NaN, Double.NaN}, {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}};

        for (double[] c : cases) {
            int argb = STBNGenerator.calculatePixelColor(c[0], c[1]);

            int a = (argb >>> 24) & 0xFF;
            int b = (argb >>> 16) & 0xFF;
            int g = (argb >>> 8) & 0xFF;
            int r = argb & 0xFF;

            Assertions.assertEquals(0xFF, a, "alpha must be opaque");
            Assertions.assertTrue(r >= 0 && r <= 255, "r out of range");
            Assertions.assertTrue(g >= 0 && g <= 255, "g out of range");
            Assertions.assertTrue(b >= 0 && b <= 255, "b out of range");
        }
    }

    @Test
    void generateAsync_smallDims_producesNormalizedFields() throws Exception {
        int w = 16;
        int h = 16;
        int d = 8;

        STBNGenerator.STBNFields fields =
                STBNGenerator.generateAsync(w, h, d, 123456789L).get(10, TimeUnit.SECONDS);
        if (fields == null) {
            // Cache hit path: generateAsync intentionally returns null when a valid cache exists.
            Assertions.assertTrue(STBNCache.isCacheValid(w, h, d),
                    "Expected cache to be valid when generation returned null");
            return;
        }

        int expectedSize = w * h * d;
        Assertions.assertEquals(expectedSize, fields.uField().length);
        Assertions.assertEquals(expectedSize, fields.vField().length);

        assertAllInRangeZeroToOne(fields.uField());
        assertAllInRangeZeroToOne(fields.vField());
    }

    private static void assertAllInRangeZeroToOne(double[] values) {
        for (double v : values) {
            Assertions.assertTrue(v >= 0.0 && v <= 1.0, "value out of range: " + v);
        }
    }
}
