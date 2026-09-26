package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.ResourceDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.MenuBlurScope;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

public final class MenuBlurGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    var pool = context.computeOnClient(client -> new CrossFrameResourcePool(3));
    try {
      check(context, pool, GpuFormat.RGBA16_FLOAT, true);
      check(context, pool, GpuFormat.RGBA32_FLOAT, true);
      check(context, pool, GpuFormat.RGBA16_FLOAT, true);
      check(context, pool, GpuFormat.RGBA16_FLOAT, false);
      context.runOnClient(
          client -> {
            var chain =
                client
                    .getShaderManager()
                    .getPostChain(
                        Identifier.withDefaultNamespace("blur"), LevelTargetBundle.MAIN_TARGETS);
            var target = new TextureTarget("CBBG blur failure", 4, 4, GpuFormat.RGBA16_FLOAT, null);
            RuntimeException injected = new RuntimeException("Injected blur allocation failure");
            try {
              chain.process(
                  target,
                  new GraphicsResourceAllocator() {
                    @Override
                    public <T> T acquire(ResourceDescriptor<T> descriptor) {
                      throw injected;
                    }

                    @Override
                    public <T> void release(ResourceDescriptor<T> descriptor, T resource) {
                      descriptor.free(resource);
                    }
                  });
              throw new AssertionError("Expected allocation failure");
            } catch (RuntimeException failure) {
              if (failure != injected) {
                throw failure;
              }
            } finally {
              target.destroyBuffers();
            }
            if (MenuBlurScope.format() != null) {
              throw new AssertionError("Blur scope leaked after failure");
            }
            var other =
                client
                    .getShaderManager()
                    .getPostChain(
                        Identifier.withDefaultNamespace("creeper"), LevelTargetBundle.MAIN_TARGETS);
            var unrelated =
                new TextureTarget("CBBG unrelated chain", 4, 4, GpuFormat.RGBA8_UNORM, null);
            try {
              RenderSystem.getDevice()
                  .createCommandEncoder()
                  .clearColorTexture(
                      unrelated.getColorTexture(), new Vector4f(0.5f, 0.5f, 0.5f, 1));
              MenuBlurScope.run(
                  GpuFormat.RGBA32_FLOAT,
                  () -> {
                    other.process(
                        unrelated,
                        new GraphicsResourceAllocator() {
                          @Override
                          public <T> T acquire(ResourceDescriptor<T> descriptor) {
                            if (MenuBlurScope.format() != null
                                || !(descriptor instanceof RenderTargetDescriptor rt)
                                || rt.color().format() != GpuFormat.RGBA8_UNORM) {
                              throw new AssertionError(
                                  "Unrelated post chain inherited the blur scope");
                            }
                            return GraphicsResourceAllocator.UNPOOLED.acquire(descriptor);
                          }

                          @Override
                          public <T> void release(ResourceDescriptor<T> descriptor, T resource) {
                            GraphicsResourceAllocator.UNPOOLED.release(descriptor, resource);
                          }
                        });
                    if (MenuBlurScope.format() != GpuFormat.RGBA32_FLOAT) {
                      throw new AssertionError("Nested chain did not restore the outer blur scope");
                    }
                  });
            } finally {
              unrelated.destroyBuffers();
            }
          });
    } finally {
      context.runOnClient(client -> pool.close());
    }
  }

  private static void check(
      ClientGameTestContext context,
      CrossFrameResourcePool pool,
      GpuFormat format,
      boolean enabled) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    context.runOnClient(
        client -> {
          var device = RenderSystem.getDevice();
          var target = new TextureTarget("CBBG blur precision", 4, 4, format, null);
          var buffer = device.createBuffer(() -> "CBBG blur readback", 9, 16L * format.blockSize());
          var original = CbbgConfig.get().mode();
          int[] acquisitions = {0};
          try {
            CbbgConfig.setMode(enabled ? CbbgConfig.Mode.ENABLED : CbbgConfig.Mode.DISABLED);
            device
                .createCommandEncoder()
                .clearColorTexture(
                    target.getColorTexture(),
                    new Vector4f(1.0f / 1024, 3.0f / 1024, 5.0f / 1024, 1));
            var chain =
                client
                    .getShaderManager()
                    .getPostChain(
                        Identifier.withDefaultNamespace("blur"), LevelTargetBundle.MAIN_TARGETS);
            if (chain == null) {
              throw new AssertionError("Vanilla blur chain is unavailable");
            }
            chain.process(
                target,
                new GraphicsResourceAllocator() {
                  @Override
                  public <T> T acquire(ResourceDescriptor<T> descriptor) {
                    GpuFormat expected = enabled ? format : GpuFormat.RGBA8_UNORM;
                    if (!(descriptor instanceof RenderTargetDescriptor rt)
                        || rt.color() == null
                        || rt.color().format() != expected) {
                      throw new AssertionError("Wrong blur descriptor format");
                    }
                    T resource = pool.acquire(descriptor);
                    if (((RenderTarget) resource).getColorTexture().getFormat() != expected) {
                      throw new AssertionError("Reused a pooled target with the wrong format");
                    }
                    acquisitions[0]++;
                    return resource;
                  }

                  @Override
                  public <T> void release(ResourceDescriptor<T> descriptor, T resource) {
                    pool.release(descriptor, resource);
                  }
                });
            if (acquisitions[0] == 0 || MenuBlurScope.format() != null) {
              throw new AssertionError("Blur did not allocate targets or restore its scope");
            }
            device
                .createCommandEncoder()
                .copyTextureToBuffer(
                    target.getColorTexture(),
                    buffer,
                    0,
                    () -> {
                      try (var mapped = buffer.map(true, false)) {
                        var pixels = mapped.data().order(ByteOrder.nativeOrder());
                        float[] expected =
                            enabled
                                ? new float[] {1.0f / 1024, 3.0f / 1024, 5.0f / 1024, 1}
                                : new float[] {0, 1.0f / 255, 1.0f / 255, 1};
                        for (int channel = 0; channel < 4; channel++) {
                          float actual =
                              format == GpuFormat.RGBA16_FLOAT
                                  ? Float.float16ToFloat(pixels.getShort(channel * 2))
                                  : pixels.getFloat(channel * 4);
                          if (!Float.isFinite(actual)
                              || Math.abs(actual - expected[channel]) > 0.00001f) {
                            throw new AssertionError(
                                "Blur channel "
                                    + channel
                                    + " lost float precision: "
                                    + actual
                                    + ", expected "
                                    + expected[channel]);
                          }
                        }
                        result.complete(null);
                      } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                      } finally {
                        buffer.close();
                        target.destroyBuffers();
                      }
                    },
                    0);
          } catch (Throwable failure) {
            buffer.close();
            target.destroyBuffers();
            result.completeExceptionally(failure);
          } finally {
            CbbgConfig.setMode(original);
          }
        });
    context.waitFor(client -> result.isDone(), 200);
    result.join();
  }
}
