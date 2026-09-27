package com.qb20nh.cbbg.gametest;

import static com.qb20nh.cbbg.gametest.RenderScaleTestAccess.call;
import static com.qb20nh.cbbg.gametest.RenderScaleTestAccess.field;
import static com.qb20nh.cbbg.gametest.RenderScaleTestAccess.set;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.DitherPass;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import org.joml.Vector4f;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class RenderScaleGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    boolean expected =
        java.util.List.of(
                Objects.requireNonNull(System.getProperty("cbbg.test.compat", "none")).split("\\+"))
            .contains("renderscale");
    if (FabricLoader.getInstance().isModLoaded("renderscale") != expected
        || RenderScaleCompat.isLoaded() != expected) {
      throw new AssertionError("RenderScale presence does not match the requested fixture");
    }
    if (!expected) {
      return;
    }
    Object renderer =
        context.computeOnClient(client -> Objects.requireNonNull(call(null, "getInstance")));
    Object config =
        context.computeOnClient(client -> Objects.requireNonNull(call(null, "getConfig")));
    CbbgConfig original = CbbgConfig.get();
    Object scale = field(config, "scale");
    Object fsr = field(config, "fsr");
    Object linear = field(config, "forceLinear");
    Object frameRate = field(config, "targetFrameRate");
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      context.runOnClient(
          client -> {
            CbbgConfig.setMode(CbbgConfig.Mode.ENABLED);
            CbbgConfig.setStrength(1);
            CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA16F);
          });
      context.waitFor(client -> DitherController.isReady(), 600);
      for (float value : new float[] {0.5f, 1, 2}) {
        configure(context, renderer, config, value, false, false);
        check(context, renderer, Math.min(value, 1), value, false);
      }
      configure(context, renderer, config, 0.5f, false, true);
      check(context, renderer, 0.5f, 0.5f, false);
      configure(context, renderer, config, 0.5f, true, false);
      check(context, renderer, 0.5f, 0.5f, true);
      context.runOnClient(client -> CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA32F));
      context.waitTicks(3);
      check(context, renderer, 0.5f, 0.5f, true);

      // Freeze a deterministic dynamic scale below its configured ceiling.
      configure(context, renderer, config, 1, false, false);
      context.runOnClient(
          client -> {
            set(config, "targetFrameRate", 60);
            call(field(renderer, "dynamicScale"), "reset", new Class<?>[] {double.class}, 0.5);
            call(renderer, "resizeRenderTarget");
            if (RenderScaleCompat.getDitherCoordScale() != 0.5f) {
              throw new AssertionError(
                  "Dithering used the configured ceiling instead of dynamic scale");
            }
          });
      context.runOnClient(
          client -> {
            set(config, "targetFrameRate", 0);
            CbbgConfig.setMode(CbbgConfig.Mode.DISABLED);
          });
      context.waitTicks(3);
      context.runOnClient(
          client -> {
            for (String name : new String[] {"renderTarget", "fsrIntermediateTarget"}) {
              RenderTarget target = (RenderTarget) field(renderer, name);
              if (target == null
                  || Objects.requireNonNull(target.getColorTexture()).getFormat()
                      != GpuFormat.RGBA8_UNORM) {
                throw new AssertionError("Disabling retained a float RenderScale target: " + name);
              }
            }
          });
    } finally {
      context.runOnClient(
          client -> {
            set(config, "scale", scale);
            set(config, "fsr", fsr);
            set(config, "forceLinear", linear);
            set(config, "targetFrameRate", frameRate);
            call(renderer, "onResolutionChanged");
            CbbgConfig.setMode(original.mode());
            CbbgConfig.setStrength(original.strength());
            CbbgConfig.setPixelFormat(original.pixelFormat());
          });
    }
  }

  private static void configure(
      ClientGameTestContext context,
      Object renderer,
      Object config,
      float scale,
      boolean fsr,
      boolean linear) {
    context.runOnClient(
        client -> {
          set(config, "scale", scale);
          set(config, "fsr", fsr);
          set(config, "forceLinear", linear);
          set(config, "targetFrameRate", 0);
          call(renderer, "onResolutionChanged");
        });
    context.waitTicks(3);
  }

  private static void check(
      ClientGameTestContext context,
      Object renderer,
      float coordinateScale,
      float renderScale,
      boolean fsr) {
    CompletableFuture<int[]> actual = new CompletableFuture<>();
    CompletableFuture<int[]> expected = new CompletableFuture<>();
    CompletableFuture<@Nullable Void> precision = new CompletableFuture<>();
    AtomicReference<@Nullable DitherPass> reference = new AtomicReference<>();
    try {
      context.runOnClient(
          client -> {
            if (RenderScaleCompat.getDitherCoordScale() != coordinateScale) {
              throw new AssertionError("Incorrect RenderScale coordinate factor");
            }
            RenderTarget scaled =
                (RenderTarget) Objects.requireNonNull(field(renderer, "renderTarget"));
            RenderTarget main = client.gameRenderer.mainRenderTarget();
            GpuFormat format = Objects.requireNonNull(main.getColorTexture()).getFormat();
            if (scaled.width != Math.max((int) (main.width * renderScale), 1)
                || scaled.height != Math.max((int) (main.height * renderScale), 1)
                || Objects.requireNonNull(scaled.getColorTexture()).getFormat() != format) {
              throw new AssertionError("RenderScale dimensions or precision are incorrect");
            }
            var device = RenderSystem.getDevice();
            var encoder = device.createCommandEncoder();
            encoder.clearColorTexture(
                scaled.getColorTexture(), new Vector4f(1.0f / 1024, 3.0f / 1024, 5.0f / 1024, 1));
            call(
                renderer,
                "blitAndBlendToTexture",
                new Class<?>[] {RenderTarget.class, RenderTarget.class, FilterMode.class},
                scaled,
                main,
                Boolean.TRUE.equals(
                        call(Objects.requireNonNull(call(null, "getConfig")), "getFilter"))
                    ? FilterMode.LINEAR
                    : FilterMode.NEAREST);
            if (fsr
                && Objects.requireNonNull(
                            ((RenderTarget)
                                    Objects.requireNonNull(
                                        field(renderer, "fsrIntermediateTarget")))
                                .getColorTexture())
                        .getFormat()
                    != format) {
              throw new AssertionError("FSR intermediate lost float precision");
            }
            var buffer =
                device.createBuffer(
                    () -> "RenderScale precision readback",
                    9,
                    (long) main.width * main.height * format.blockSize());
            encoder.copyTextureToBuffer(
                main.getColorTexture(),
                buffer,
                0,
                () -> {
                  try (var mapped = buffer.map(true, false)) {
                    var bytes = mapped.data().order(ByteOrder.nativeOrder());
                    float[] values = {1.0f / 1024, 3.0f / 1024, 5.0f / 1024, 1};
                    for (int i = 0; i < 4; i++) {
                      float value =
                          format == GpuFormat.RGBA16_FLOAT
                              ? Float.float16ToFloat(bytes.getShort(i * 2))
                              : bytes.getFloat(i * 4);
                      // FSR uses approximate arithmetic; this bound is still over 100 times
                      // smaller than an 8-bit step and catches intermediate quantization.
                      float tolerance = fsr ? 1.0f / 32768 : 0.00001f;
                      if (!Float.isFinite(value) || Math.abs(value - values[i]) > tolerance) {
                        throw new AssertionError(
                            "RenderScale blit quantized channel " + i + ": " + value);
                      }
                    }
                    precision.complete(null);
                  } catch (Throwable failure) {
                    precision.completeExceptionally(failure);
                  } finally {
                    buffer.close();
                  }
                },
                0);
            encoder.clearColorTexture(
                main.getColorTexture(),
                new Vector4f(127.25f / 255, 127.25f / 255, 127.25f / 255, 1));
            capture(main, actual);
            DitherPass pass = new DitherPass();
            reference.set(pass);
            capture(
                pass.render(
                    Objects.requireNonNull(main.getColorTextureView()),
                    (GpuTextureView)
                        Objects.requireNonNull(field(DitherController.class, "noiseView")),
                    1,
                    coordinateScale,
                    coordinateScale,
                    false),
                expected);
          });
      context.waitFor(client -> actual.isDone() && expected.isDone() && precision.isDone(), 200);
      precision.join();
      if (!Arrays.equals(actual.join(), expected.join())) {
        throw new AssertionError("RenderScale screenshot used the wrong dither pixel grid");
      }
    } finally {
      context.runOnClient(
          client -> {
            DitherPass current = reference.get();
            if (current != null) {
              current.close();
            }
          });
    }
  }

  private static void capture(RenderTarget target, CompletableFuture<int[]> result) {
    Screenshot.takeScreenshot(
        target,
        image -> {
          try (image) {
            result.complete(image.getPixels());
          } catch (Throwable failure) {
            result.completeExceptionally(failure);
          }
        });
  }
}
