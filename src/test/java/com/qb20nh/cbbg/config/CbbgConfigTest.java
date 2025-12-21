package com.qb20nh.cbbg.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CbbgConfigTest {

    @Test
    void constructor_nullMode_defaultsToEnabled() {
        CbbgConfig cfg = new CbbgConfig(null, CbbgConfig.PixelFormat.RGBA16F, 128, 64, 0L, 1.0f,
                true, true);
        Assertions.assertEquals(CbbgConfig.Mode.ENABLED, cfg.mode());
    }

    @Test
    void constructor_nullPixelFormat_defaultsToRgba16f() {
        CbbgConfig cfg = new CbbgConfig(CbbgConfig.Mode.ENABLED, null, 128, 64, 0L, 1.0f, true,
                true);
        Assertions.assertEquals(CbbgConfig.PixelFormat.RGBA16F, cfg.pixelFormat());
    }

    @Test
    void constructor_rgba8PixelFormat_normalizesToRgba16f() {
        CbbgConfig cfg = new CbbgConfig(CbbgConfig.Mode.ENABLED, CbbgConfig.PixelFormat.RGBA8, 128,
                64, 0L, 1.0f, true, true);
        Assertions.assertEquals(CbbgConfig.PixelFormat.RGBA16F, cfg.pixelFormat());
    }

    @Test
    void constructor_clampsStbnSizeAndDepth() {
        CbbgConfig cfg = new CbbgConfig(CbbgConfig.Mode.ENABLED, CbbgConfig.PixelFormat.RGBA16F, -5,
                999, 0L, 1.0f, true, true);
        Assertions.assertEquals(16, cfg.stbnSize());
        Assertions.assertEquals(128, cfg.stbnDepth());
    }

    @Test
    void constructor_clampsStrengthAndHandlesNaNInfinity() {
        Assertions.assertEquals(0.5f,
                new CbbgConfig(CbbgConfig.Mode.ENABLED, CbbgConfig.PixelFormat.RGBA16F, 128, 64, 0L,
                        -999.0f, true, true).strength(),
                0.0f);
        Assertions.assertEquals(4.0f,
                new CbbgConfig(CbbgConfig.Mode.ENABLED, CbbgConfig.PixelFormat.RGBA16F, 128, 64, 0L,
                        999.0f, true, true).strength(),
                0.0f);
        Assertions.assertEquals(1.0f,
                new CbbgConfig(CbbgConfig.Mode.ENABLED, CbbgConfig.PixelFormat.RGBA16F, 128, 64, 0L,
                        Float.NaN, true, true).strength(),
                0.0f);
        Assertions.assertEquals(1.0f,
                new CbbgConfig(CbbgConfig.Mode.ENABLED, CbbgConfig.PixelFormat.RGBA16F, 128, 64, 0L,
                        Float.POSITIVE_INFINITY, true, true).strength(),
                0.0f);
    }

    @Test
    void mode_isActiveMatchesDisabledOnly() {
        Assertions.assertTrue(CbbgConfig.Mode.ENABLED.isActive());
        Assertions.assertTrue(CbbgConfig.Mode.DEMO.isActive());
        Assertions.assertFalse(CbbgConfig.Mode.DISABLED.isActive());
    }

    @Test
    void enum_serializedNames_matchExpectedValues() {
        Assertions.assertEquals("enabled", CbbgConfig.Mode.ENABLED.getSerializedName());
        Assertions.assertEquals("disabled", CbbgConfig.Mode.DISABLED.getSerializedName());
        Assertions.assertEquals("demo", CbbgConfig.Mode.DEMO.getSerializedName());

        Assertions.assertEquals("rgba8", CbbgConfig.PixelFormat.RGBA8.getSerializedName());
        Assertions.assertEquals("rgba16f", CbbgConfig.PixelFormat.RGBA16F.getSerializedName());
        Assertions.assertEquals("rgba32f", CbbgConfig.PixelFormat.RGBA32F.getSerializedName());
    }
}




