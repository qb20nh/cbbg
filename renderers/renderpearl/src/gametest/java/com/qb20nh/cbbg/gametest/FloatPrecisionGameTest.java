package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Vector4f;

/** Detects accidental RGBA8 quantization before the final conversion pass. */
public final class FloatPrecisionGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    for (GpuFormat format : new GpuFormat[] {GpuFormat.RGBA16_FLOAT, GpuFormat.RGBA32_FLOAT}) {
      CompletableFuture<Void> result = new CompletableFuture<>();
      context.runOnClient(
          client -> {
            var device = RenderSystem.getDevice();
            TextureTarget source = new TextureTarget("CBBG precision input", 1, 1, format, null);
            TextureTarget target = new TextureTarget("CBBG precision output", 1, 1, format, null);
            var buffer =
                device.createBuffer(() -> "CBBG precision readback", 9, format.blockSize());
            try {
              var encoder = device.createCommandEncoder();
              encoder.clearColorTexture(
                  source.getColorTexture(), new Vector4f(1.0f / 1024, 3.0f / 1024, 5.0f / 1024, 1));
              try (RenderPass pass =
                  encoder.createRenderPass(
                      () -> "CBBG precision", target.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform(
                    "InSampler",
                    source.getColorTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
              }
              encoder.copyTextureToBuffer(
                  target.getColorTexture(),
                  buffer,
                  0,
                  () -> {
                    try (var mapped = buffer.map(true, false)) {
                      var pixels = mapped.data().order(ByteOrder.nativeOrder());
                      int[] halfBits = {0x1400, 0x1a00, 0x1d00, 0x3c00};
                      float[] floats = {1.0f / 1024, 3.0f / 1024, 5.0f / 1024, 1};
                      for (int i = 0; i < 4; i++) {
                        boolean exact =
                            format == GpuFormat.RGBA16_FLOAT
                                ? Short.toUnsignedInt(pixels.getShort(i * 2)) == halfBits[i]
                                : pixels.getFloat(i * 4) == floats[i];
                        if (!exact) {
                          throw new AssertionError(
                              format + " lost float precision in channel " + i);
                        }
                      }
                      result.complete(null);
                    } catch (Throwable failure) {
                      result.completeExceptionally(failure);
                    } finally {
                      buffer.close();
                      target.destroyBuffers();
                      source.destroyBuffers();
                    }
                  },
                  0);
            } catch (Throwable failure) {
              buffer.close();
              target.destroyBuffers();
              source.destroyBuffers();
              result.completeExceptionally(failure);
            }
          });
      context.waitFor(client -> result.isDone(), 200);
      result.join();
    }
  }
}
