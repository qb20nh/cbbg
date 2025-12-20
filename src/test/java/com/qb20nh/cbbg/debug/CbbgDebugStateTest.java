package com.qb20nh.cbbg.debug;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CbbgDebugStateTest {

    @Test
    void clear_resetsAllValues() {
        CbbgDebugState.update(1, 2, 3, true);
        Assertions.assertNotEquals(0L, CbbgDebugState.getLastUpdateNanos());

        CbbgDebugState.clear();
        Assertions.assertEquals(-1, CbbgDebugState.getMainInternalFormat());
        Assertions.assertEquals(-1, CbbgDebugState.getLightmapInternalFormat());
        Assertions.assertEquals(-1, CbbgDebugState.getDefaultFbEncoding());
        Assertions.assertFalse(CbbgDebugState.isFramebufferSrgb());
        Assertions.assertEquals(0L, CbbgDebugState.getLastUpdateNanos());
    }

    @Test
    void update_storesValuesAndTimestamp() {
        CbbgDebugState.clear();
        long before = System.nanoTime();

        CbbgDebugState.update(10, null, 30, false);

        Assertions.assertEquals(10, CbbgDebugState.getMainInternalFormat());
        Assertions.assertEquals(-1, CbbgDebugState.getLightmapInternalFormat());
        Assertions.assertEquals(30, CbbgDebugState.getDefaultFbEncoding());
        Assertions.assertFalse(CbbgDebugState.isFramebufferSrgb());
        Assertions.assertTrue(CbbgDebugState.getLastUpdateNanos() >= before);
    }
}


