package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.qb20nh.cbbg.config.CbbgConfig;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;
import org.slf4j.LoggerFactory;

@NullMarked
public final class MainTargetsGameTest implements FabricClientGameTest {
  @Override
  // GPU ownership and recreation require checking the exact resource instances.
  @SuppressWarnings("ReferenceEquality")
  public void runTest(ClientGameTestContext context) {
    CbbgConfig original = CbbgConfig.get();
    LoggerFactory.getLogger("cbbg-test").info("MainTargets setup: disable");
    context.runOnClient(client -> CbbgConfig.setMode(CbbgConfig.Mode.DISABLED));
    context.waitTicks(3);
    LoggerFactory.getLogger("cbbg-test").info("MainTargets setup: allocate custom target");
    TextureTarget custom =
        context.computeOnClient(
            client ->
                new TextureTarget(
                    "Other Mod / initFbo", 13, 7, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT));
    var customColor = context.computeOnClient(client -> custom.getColorTexture());
    var customDepth = context.computeOnClient(client -> custom.getDepthTexture());
    LoggerFactory.getLogger("cbbg-test").info("MainTargets setup: allocate live main target");
    MainTarget live = context.computeOnClient(client -> new MainTarget(16, 8));
    LoggerFactory.getLogger("cbbg-test")
        .info("MainTargets setup: allocate and destroy closed main target");
    MainTarget closed =
        context.computeOnClient(
            client -> {
              MainTarget target = new MainTarget(2, 2);
              target.destroyBuffers();
              return target;
            });
    try {
      for (var mode : new CbbgConfig.Mode[] {CbbgConfig.Mode.ENABLED, CbbgConfig.Mode.DISABLED}) {
        for (var precision :
            new CbbgConfig.PixelFormat[] {
              CbbgConfig.PixelFormat.RGBA32F, CbbgConfig.PixelFormat.RGBA16F
            }) {
          var previous = context.computeOnClient(client -> live.getColorTexture());
          LoggerFactory.getLogger("cbbg-test")
              .info("MainTargets transition mode={} precision={}", mode, precision);
          context.runOnClient(
              client -> {
                CbbgConfig.setMode(mode);
                CbbgConfig.setPixelFormat(precision);
              });
          context.waitTicks(3);
          context.runOnClient(
              client -> {
                GpuFormat expected =
                    !mode.isActive()
                        ? GpuFormat.RGBA8_UNORM
                        : precision == CbbgConfig.PixelFormat.RGBA32F
                            ? GpuFormat.RGBA32_FLOAT
                            : GpuFormat.RGBA16_FLOAT;
                if (Objects.requireNonNull(live.getColorTexture()).getFormat() != expected
                    || !Objects.requireNonNull(previous).isClosed()
                    || live.width != 16
                    || live.height != 8) {
                  throw new AssertionError(
                      "Secondary main target did not follow the precision policy");
                }
                if (closed.getColorTexture() != null) {
                  throw new AssertionError("Precision change resurrected a destroyed target");
                }
                if (custom.getColorTexture() != customColor
                    || custom.getDepthTexture() != customDepth
                    || Objects.requireNonNull(customColor).getFormat() != GpuFormat.RGBA8_UNORM) {
                  throw new AssertionError("Precision change mutated a mod-owned framebuffer");
                }
              });
          LoggerFactory.getLogger("cbbg-test")
              .info("MainTargets transition verified mode={} precision={}", mode, precision);
        }
      }
      context.runOnClient(
          client -> {
            custom.resize(19, 11);
            if (!Objects.requireNonNull(customColor).isClosed()
                || !Objects.requireNonNull(customDepth).isClosed()
                || Objects.requireNonNull(custom.getColorTexture()).getFormat()
                    != GpuFormat.RGBA8_UNORM
                || Objects.requireNonNull(custom.getDepthTexture()).getFormat()
                    != customDepth.getFormat()) {
              throw new AssertionError(
                  "Mod-owned framebuffer recreation changed attachment formats");
            }
          });
    } finally {
      context.runOnClient(
          client -> {
            custom.destroyBuffers();
            live.destroyBuffers();
            closed.destroyBuffers();
            CbbgConfig.setMode(original.mode());
            CbbgConfig.setPixelFormat(original.pixelFormat());
          });
    }
  }
}
