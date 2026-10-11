package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseRenderScale {
  private ReleaseRenderScale() {}

  static boolean selected() {
    boolean expected =
        List.of(Objects.requireNonNull(System.getProperty("cbbg.test.compat", "none")).split("\\+"))
            .contains("renderscale");
    if (FabricLoader.getInstance().isModLoaded("renderscale") != expected) {
      throw new AssertionError("RenderScale presence differs from the selected profile");
    }
    return expected;
  }

  static void awaitHalfScale(ClientGameTestContext context, int expectedFormat) {
    context.waitFor(
        client -> {
          RenderTarget main = client.getMainRenderTarget();
          RenderTarget scaled =
              (RenderTarget)
                  RenderScaleTestAccess.field(
                      Objects.requireNonNull(RenderScaleTestAccess.call(null, "getInstance")),
                      "renderTarget");
          return scaled != null
              && scaled.width == ReleaseWorldCapture.scaledDimension(main.width, 0.5f)
              && scaled.height == ReleaseWorldCapture.scaledDimension(main.height, 0.5f)
              && scaled.getColorTextureId() > 0
              && ReleaseLifecycleGameTest.format(scaled.getColorTextureId()) == expectedFormat;
        },
        600);
  }
}
