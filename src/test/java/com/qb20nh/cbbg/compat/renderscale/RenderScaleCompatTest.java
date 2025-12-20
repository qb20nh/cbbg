package com.qb20nh.cbbg.compat.renderscale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RenderScaleCompatTest {

    @Test
    void whenRenderScaleNotLoaded_helpersAreNoOps() {
        Assertions.assertFalse(RenderScaleCompat.isLoaded());
        Assertions.assertFalse(RenderScaleCompat.isRenderScaleColorTextureLabel(() -> "RenderScale / Color"));
        Assertions.assertEquals(1.0F, RenderScaleCompat.getDitherCoordScale(), 0.0F);
    }
}


