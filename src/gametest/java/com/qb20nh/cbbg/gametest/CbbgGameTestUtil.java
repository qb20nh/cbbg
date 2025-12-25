package com.qb20nh.cbbg.gametest;

import com.qb20nh.cbbg.config.CbbgConfig;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

final class CbbgGameTestUtil {

    private CbbgGameTestUtil() {}

    static void restoreConfig(CbbgConfig cfg) {
        if (cfg == null) {
            return;
        }
        CbbgConfig.setMode(cfg.mode());
        CbbgConfig.setPixelFormat(cfg.pixelFormat());
        CbbgConfig.setStrength(cfg.strength());
        CbbgConfig.setStbnSize(cfg.stbnSize());
        CbbgConfig.setStbnDepth(cfg.stbnDepth());
        CbbgConfig.setStbnSeed(cfg.stbnSeed());
        CbbgConfig.setNotifyChat(cfg.notifyChat());
        CbbgConfig.setNotifyToast(cfg.notifyToast());
    }

    static int glInternalFor(CbbgConfig.PixelFormat format) {
        if (format == null) {
            return GL11.GL_RGBA8;
        }
        return switch (format) {
            case RGBA8 -> GL11.GL_RGBA8;
            case RGBA16F -> GL30.GL_RGBA16F;
            case RGBA32F -> GL30.GL_RGBA32F;
        };
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void assertEquals(int expected, int actual, String name) {
        if (expected != actual) {
            throw new AssertionError(
                    "Mismatch for " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    static void assertEquals(Object expected, Object actual, String name) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    "Mismatch for " + name + ": expected=" + expected + " actual=" + actual);
        }
    }
}
