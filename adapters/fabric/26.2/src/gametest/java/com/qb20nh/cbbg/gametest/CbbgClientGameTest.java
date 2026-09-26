package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.render.CbbgDither;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

@NullMarked
public class CbbgClientGameTest implements FabricClientGameTest {

  @Override
  public void runTest(ClientGameTestContext context) {
    if (!FabricLoader.getInstance().isModLoaded("cbbg")) {
      throw new AssertionError("Expected main mod 'cbbg' to be loaded");
    }

    try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
      singleplayer.getConnection().waitForChunksRender();
      if (RenderScaleCompat.isLoaded()) {
        testRenderScale(context);
      }
      context.takeScreenshot("cbbg-client-smoke");
    }
  }

  private static void testRenderScale(ClientGameTestContext context) {
    // Finish at the default scale so subsequent tests start with normal resolution.
    for (float scale : new float[] {0.5F, 2.0F, 1.0F}) {
      context.runOnClient(
          client -> {
            try {
              Class<?> api = Class.forName("dev.zelo.renderscale.RenderScale");
              Object config = Objects.requireNonNull(api.getMethod("getConfig").invoke(null));
              config.getClass().getField("scale").setFloat(config, scale);
              Object instance = api.getMethod("getInstance").invoke(null);
              api.getMethod("onResolutionChanged").invoke(instance);
              float expected = Math.min(scale, 1.0F);
              if (RenderScaleCompat.getDitherCoordScale() != expected) {
                throw new AssertionError(
                    "RenderScale coordinate scale: expected "
                        + expected
                        + ", got "
                        + RenderScaleCompat.getDitherCoordScale());
              }
              RenderTarget target =
                  (RenderTarget) Objects.requireNonNull(api.getField("renderTarget").get(instance));
              int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
              try {
                GL11.glBindTexture(
                    GL11.GL_TEXTURE_2D,
                    ((GlTexture) Objects.requireNonNull(target.getColorTexture())).glId());
                int format =
                    GL11.glGetTexLevelParameteri(
                        GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                if (format != GL30.GL_RGBA16F) {
                  throw new AssertionError("RenderScale target lost float precision: " + format);
                }
              } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
              }
            } catch (ReflectiveOperationException e) {
              throw new LinkageError("RenderScale integration API changed", e);
            }
          });
      context.waitTicks(3);
      context.runOnClient(
          client -> {
            if (CbbgDither.isDisabled()) {
              throw new AssertionError("Dithering disabled itself at RenderScale " + scale);
            }
          });
      context.takeScreenshot("cbbg-renderscale-" + scale);
    }
  }
}
