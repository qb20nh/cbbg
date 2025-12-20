package com.qb20nh.cbbg.render;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GlFormatOverrideTest {

    @Test
    void mainTargetColor_pushPopBehavesLikeDepthCounter() {
        Assertions.assertFalse(GlFormatOverride.isMainTargetColor());

        GlFormatOverride.pushMainTargetColor();
        Assertions.assertTrue(GlFormatOverride.isMainTargetColor());

        GlFormatOverride.pushMainTargetColor();
        Assertions.assertTrue(GlFormatOverride.isMainTargetColor());

        GlFormatOverride.popMainTargetColor();
        Assertions.assertTrue(GlFormatOverride.isMainTargetColor());

        GlFormatOverride.popMainTargetColor();
        Assertions.assertFalse(GlFormatOverride.isMainTargetColor());
    }

    @Test
    void forcedFormat_isThreadLocal() throws ExecutionException, InterruptedException {
        GlFormatOverride.setForcedFormat(123);
        Assertions.assertEquals(123, GlFormatOverride.getForcedFormat());

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> otherThreadValue =
                    exec.submit(() -> GlFormatOverride.getForcedFormat());
            Assertions.assertNull(otherThreadValue.get(), "thread-local value must not leak");
        } finally {
            exec.shutdownNow();
            GlFormatOverride.setForcedFormat(null);
        }
    }

    @Test
    void pushPopFormat_setsAndClears() {
        Assertions.assertNull(GlFormatOverride.getFormat());

        GlFormatOverride.pushFormat(456);
        Assertions.assertEquals(456, GlFormatOverride.getFormat());

        GlFormatOverride.popFormat();
        Assertions.assertNull(GlFormatOverride.getFormat());
    }
}


