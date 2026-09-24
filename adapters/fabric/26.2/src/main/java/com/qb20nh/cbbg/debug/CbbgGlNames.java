package com.qb20nh.cbbg.debug;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

public final class CbbgGlNames {
    private static final String HEX_FORMAT = "0x%04X";

    private CbbgGlNames() {}

    public static String glInternalName(int internal) {
        return switch (internal) {
            case GL11.GL_RGBA8 -> "GL_RGBA8(0x8058)";
            case GL30.GL_RGBA16F -> "GL_RGBA16F(0x881A)";
            default -> String.format(HEX_FORMAT, internal);
        };
    }

    public static String glEncodingName(int encoding) {
        return switch (encoding) {
            case GL21.GL_SRGB -> "GL_SRGB(0x8C40)";
            case GL11.GL_LINEAR -> "GL_LINEAR(0x2601)";
            default -> String.format(HEX_FORMAT, encoding);
        };
    }

    /** Shorter names for the F3 overlay. */
    public static String glInternalShort(int internal) {
        return switch (internal) {
            case GL11.GL_RGBA8 -> "RGBA8";
            case GL30.GL_RGBA16F -> "RGBA16F";
            default -> String.format(HEX_FORMAT, internal);
        };
    }

    /** Shorter names for the F3 overlay. */
    public static String glEncodingShort(int encoding) {
        return switch (encoding) {
            case GL21.GL_SRGB -> "SRGB";
            case GL11.GL_LINEAR -> "LIN";
            default -> String.format(HEX_FORMAT, encoding);
        };
    }
}
