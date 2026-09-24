package com.qb20nh.cbbg.math;

import java.util.Arrays;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlueNoiseTest {
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
        assertEquals("d7d43bf86a2c8a2635b0e23462b5a60f040f5a05c41500a32e7200e59729cf75",
                hex.toString());
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
