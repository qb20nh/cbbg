package com.qb20nh.cbbg.debug;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

public class CbbgGlNamesTest {

    @Test
    void knownConstants_haveStableNames() {
        Assertions.assertEquals("GL_RGBA8(0x8058)", CbbgGlNames.glInternalName(GL11.GL_RGBA8));
        Assertions.assertEquals("GL_RGBA16F(0x881A)", CbbgGlNames.glInternalName(GL30.GL_RGBA16F));

        Assertions.assertEquals("GL_SRGB(0x8C40)", CbbgGlNames.glEncodingName(GL21.GL_SRGB));
        Assertions.assertEquals("GL_LINEAR(0x2601)", CbbgGlNames.glEncodingName(GL11.GL_LINEAR));
    }

    @Test
    void unknownConstants_fallBackToHex() {
        Assertions.assertEquals("0x0000", CbbgGlNames.glInternalName(0));
        Assertions.assertEquals("0x0001", CbbgGlNames.glEncodingName(1));
    }

    @Test
    void shortNames_areCompact() {
        Assertions.assertEquals("RGBA8", CbbgGlNames.glInternalShort(GL11.GL_RGBA8));
        Assertions.assertEquals("RGBA16F", CbbgGlNames.glInternalShort(GL30.GL_RGBA16F));

        Assertions.assertEquals("SRGB", CbbgGlNames.glEncodingShort(GL21.GL_SRGB));
        Assertions.assertEquals("LIN", CbbgGlNames.glEncodingShort(GL11.GL_LINEAR));
        Assertions.assertEquals("0x0000", CbbgGlNames.glEncodingShort(0));
    }
}
