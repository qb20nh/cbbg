package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.gui.CbbgConfigScreen;
import com.qb20nh.cbbg.render.DitherController;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class SubpassRenderingGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    var original = CbbgConfig.get();
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      context.runOnClient(
          client -> {
            String actual = RenderSystem.getDevice().getDeviceInfo().backendName();
            String expected = System.getProperty("cbbg.test.backend", "opengl");
            if (!actual.equalsIgnoreCase(expected)) {
              throw new AssertionError("Expected " + expected + " but got " + actual);
            }
            CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA16F);
            CbbgConfig.setMode(CbbgConfig.Mode.DEMO);
          });
      long initialFrames = context.computeOnClient(client -> DitherController.getPresentationCount());
      context.waitFor(client -> {
        var color = client.gameRenderer.mainRenderTarget().getColorTexture();
        return color != null && color.getFormat() == GpuFormat.RGBA16_FLOAT
            && DitherController.isReady() && DitherController.getPresentationCount() > initialFrames;
      }, 600);
      context.takeScreenshot("cbbg-subpass-demo");
      context.runOnClient(client -> client.gui.setScreen(new CbbgConfigScreen(client.gui.screen())));
      context.waitForScreen(CbbgConfigScreen.class);
      context.waitTicks(5);
      context.takeScreenshot("cbbg-subpass-settings");
      context.runOnClient(client -> {
        client.gui.setScreen(null);
        CbbgConfig.setMode(CbbgConfig.Mode.DISABLED);
      });
      context.waitFor(client -> {
        var color = client.gameRenderer.mainRenderTarget().getColorTexture();
        return color != null && color.getFormat() == GpuFormat.RGBA8_UNORM;
      });
    } finally {
      context.runOnClient(client -> {
        CbbgConfig.setMode(original.mode());
        CbbgConfig.setPixelFormat(original.pixelFormat());
      });
    }
  }
}
