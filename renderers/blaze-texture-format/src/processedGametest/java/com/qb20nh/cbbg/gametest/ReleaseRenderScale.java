package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL30;

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

  static void check(ClientGameTestContext context) {
    if (!selected()) return;
    float original =
        context.computeOnClient(client -> RenderScaleTestAccess.setShaderTestScale(0.5f));
    try {
      if (original == 0.5f) throw new AssertionError("RenderScale was already at 0.5x");
      awaitHalfScale(context, GL30.GL_RGBA16F);
      ReleaseLifecycleGameTest.awaitPresentation(context);
      ReleasePixels.check(context, false, 0.5f);
      context.runOnClient(
          client -> {
            RenderTarget main = client.getMainRenderTarget();
            if (ReleaseLifecycleGameTest.format(Objects.requireNonNull(main.getColorTexture()))
                != GL30.GL_RGBA16F) {
              throw new AssertionError("RenderScale lost the main float attachment");
            }
            assertOutputSize(main);
          });
    } finally {
      context.runOnClient(client -> RenderScaleTestAccess.setShaderTestScale(original));
    }
    context.waitFor(
        client -> {
          RenderTarget main = client.getMainRenderTarget();
          RenderTarget scaled = scaledTarget();
          return scaled != null && scaled.width == main.width && scaled.height == main.height;
        },
        600);
    ReleaseLifecycleGameTest.awaitPresentation(context);
    context.runOnClient(client -> assertOutputSize(client.getMainRenderTarget()));
  }

  static void awaitHalfScale(ClientGameTestContext context, int expectedFormat) {
    context.waitFor(
        client -> {
          RenderTarget main = client.getMainRenderTarget();
          RenderTarget scaled = scaledTarget();
          return scaled != null
              && scaled.width == Math.max(main.width / 2, 1)
              && scaled.height == Math.max(main.height / 2, 1)
              && scaled.getColorTexture() != null
              && ReleaseLifecycleGameTest.format(scaled.getColorTexture()) == expectedFormat;
        },
        600);
  }

  private static @Nullable RenderTarget scaledTarget() {
    return (RenderTarget)
        RenderScaleTestAccess.field(
            Objects.requireNonNull(RenderScaleTestAccess.call(null, "getInstance")),
            "renderTarget");
  }

  private static void assertOutputSize(RenderTarget main) {
    var output = Objects.requireNonNull(ReleaseObservations.output());
    if (output.getWidth(0) != main.width || output.getHeight(0) != main.height) {
      throw new AssertionError("Dither output dimensions differ from the main target");
    }
  }
}
