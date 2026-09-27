package com.qb20nh.cbbg.compat.renderscale;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@NullMarked
class RenderScaleCompatTest {

  @Test
  void whenRenderScaleNotLoaded_helpersAreNoOps() {
    Assertions.assertFalse(RenderScaleCompat.isLoaded());
    Assertions.assertFalse(
        RenderScaleCompat.isRenderScaleColorTextureLabel(() -> "RenderScale / Color"));
    Assertions.assertEquals(1.0F, RenderScaleCompat.getDitherCoordScale(), 0.0F);
  }
}
