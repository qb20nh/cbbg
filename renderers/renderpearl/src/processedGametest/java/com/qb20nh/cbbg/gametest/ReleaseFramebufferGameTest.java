package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Verifies float main targets and framebuffers owned by other mods. */
public final class ReleaseFramebufferGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    JsonObject original = ReleaseClient.settings();
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      ReleaseClient.command(context, "mode set disabled");
      context.waitTicks(3);
      TextureTarget custom =
          context.computeOnClient(
              client ->
                  new TextureTarget(
                      "Other Mod / initFbo", 13, 7, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT));
      var customColor = context.computeOnClient(client -> custom.getColorTexture());
      var customDepth = context.computeOnClient(client -> custom.getDepthTexture());
      MainTarget live = context.computeOnClient(client -> new MainTarget(16, 8));
      MainTarget closed =
          context.computeOnClient(
              client -> {
                MainTarget target = new MainTarget(2, 2);
                target.destroyBuffers();
                return target;
              });
      try {
        for (String mode : new String[] {"enabled", "disabled"}) {
          for (String precision : new String[] {"rgba32f", "rgba16f"}) {
            var previous = context.computeOnClient(client -> live.getColorTexture());
            ReleaseClient.command(context, "mode set " + mode);
            ReleaseClient.command(context, "format set " + precision);
            GpuFormat expected =
                mode.equals("disabled")
                    ? GpuFormat.RGBA8_UNORM
                    : precision.equals("rgba32f") ? GpuFormat.RGBA32_FLOAT : GpuFormat.RGBA16_FLOAT;
            context.waitFor(client -> live.getColorTexture().getFormat() == expected, 600);
            context.waitTicks(3);
            context.runOnClient(
                client -> {
                  if (!previous.isClosed() || live.width != 16 || live.height != 8) {
                    throw new AssertionError(
                        "Secondary main target retained its old attachment or changed size");
                  }
                  if (closed.getColorTexture() != null) {
                    throw new AssertionError("Precision change recreated a destroyed target");
                  }
                  if (custom.getColorTexture() != customColor
                      || custom.getDepthTexture() != customDepth
                      || customColor.getFormat() != GpuFormat.RGBA8_UNORM) {
                    throw new AssertionError("Precision change modified a mod-owned framebuffer");
                  }
                  var main = client.gameRenderer.mainRenderTarget();
                  var old = main.getColorTexture();
                  main.resize(main.width, main.height);
                  if (!old.isClosed()
                      || main.getDepthTexture() == null
                      || main.getColorTexture().getFormat() != expected) {
                    throw new AssertionError(
                        "Main target resize failed to retain the selected format");
                  }
                });
          }
        }
        context.runOnClient(
            client -> {
              custom.resize(19, 11);
              if (!customColor.isClosed()
                  || !customDepth.isClosed()
                  || custom.getColorTexture().getFormat() != GpuFormat.RGBA8_UNORM
                  || custom.getDepthTexture().getFormat() != customDepth.getFormat()) {
                throw new AssertionError("Mod-owned framebuffer resize changed attachment formats");
              }
            });
      } finally {
        context.runOnClient(
            client -> {
              custom.destroyBuffers();
              live.destroyBuffers();
              closed.destroyBuffers();
            });
        ReleaseClient.command(
            context,
            "format set "
                + original.get("pixelFormat").getAsString().toLowerCase(java.util.Locale.ROOT));
        ReleaseClient.command(
            context,
            "mode set " + original.get("mode").getAsString().toLowerCase(java.util.Locale.ROOT));
        context.waitTicks(3);
      }
    }
  }
}
