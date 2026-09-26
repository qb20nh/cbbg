package com.qb20nh.cbbg.gametest;

import static com.qb20nh.cbbg.gametest.RenderScaleTestAccess.call;
import static com.qb20nh.cbbg.gametest.RenderScaleTestAccess.field;
import static com.qb20nh.cbbg.gametest.RenderScaleTestAccess.set;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.qb20nh.cbbg.reference.DitherReference;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Vector4f;

/** RenderScale precision and pixel-grid checks against the optimized release jar. */
public final class ReleaseRenderScaleGameTest implements FabricClientGameTest {
    // Exactly representable in both float formats, independent of clear conversion rounding.
    private static final float SOURCE = 0.5f;

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean expected = java.util.List.of(System.getProperty("cbbg.test.compat", "none").split("\\+"))
                .contains("renderscale");
        if (FabricLoader.getInstance().isModLoaded("renderscale") != expected) {
            throw new AssertionError("RenderScale presence does not match the requested fixture");
        }
        if (!expected) return;
        Object renderer = context.computeOnClient(client -> call(null, "getInstance"));
        Object config = context.computeOnClient(client -> call(null, "getConfig"));
        JsonObject previous = ReleaseClient.settings();
        float strength = previous.get("strength").getAsFloat();
        if (!Float.isFinite(strength) || strength <= 0) {
            throw new AssertionError("RenderScale pixel-grid reference requires positive finite strength");
        }
        Object scale = field(config, "scale");
        Object fsr = field(config, "fsr");
        Object linear = field(config, "forceLinear");
        Object frameRate = field(config, "targetFrameRate");
        try (var world = context.worldBuilder().create()) {
            try {
                world.getConnection().waitForChunksRender();
                ReleaseClient.command(context, "format set rgba16f");
                ReleaseClient.command(context, "mode set enabled");
                ReleaseClient.awaitFormat(context, GpuFormat.RGBA16_FLOAT);
                ReleaseClient.awaitDrawAfter(context, ProcessedRenderObservations.draws());
                for (float value : new float[] {0.5f, 1, 2}) {
                    configure(context, renderer, config, value, false, false);
                    check(context, renderer, config, Math.min(value, 1), value, false,
                            false, GpuFormat.RGBA16_FLOAT, strength, "scale-" + value);
                }
                configure(context, renderer, config, 0.5f, false, true);
                check(context, renderer, config, 0.5f, 0.5f, false, false,
                        GpuFormat.RGBA16_FLOAT, strength, "linear");
                configure(context, renderer, config, 0.5f, true, false);
                check(context, renderer, config, 0.5f, 0.5f, true, false,
                        GpuFormat.RGBA16_FLOAT, strength, "fsr16");
                ReleaseClient.command(context, "format set rgba32f");
                ReleaseClient.awaitFormat(context, GpuFormat.RGBA32_FLOAT);
                check(context, renderer, config, 0.5f, 0.5f, true, false,
                        GpuFormat.RGBA32_FLOAT, strength, "fsr32");

                configure(context, renderer, config, 1, false, false);
                // Freeze and capture within one client task: a later frame may adapt the scale.
                check(context, renderer, config, 0.5f, 0.5f, false, true,
                        GpuFormat.RGBA32_FLOAT, strength, "dynamic");
                context.runOnClient(client -> set(config, "targetFrameRate", 0));
                ReleaseClient.command(context, "mode set disabled");
                ReleaseClient.awaitFormat(context, GpuFormat.RGBA8_UNORM);
                context.runOnClient(client -> {
                    for (String name : new String[] {"renderTarget", "fsrIntermediateTarget"}) {
                        RenderTarget target = (RenderTarget) field(renderer, name);
                        if (target == null || target.getColorTexture().getFormat() != GpuFormat.RGBA8_UNORM) {
                            throw new AssertionError("Disabling retained a float RenderScale target: " + name);
                        }
                    }
                });
            } finally {
                context.runOnClient(client -> {
                    set(config, "scale", scale);
                    set(config, "fsr", fsr);
                    set(config, "forceLinear", linear);
                    set(config, "targetFrameRate", frameRate);
                    call(renderer, "onResolutionChanged");
                });
                ReleaseClient.command(context, "format set "
                        + previous.get("pixelFormat").getAsString().toLowerCase(Locale.ROOT));
                ReleaseClient.command(context, "mode set "
                        + previous.get("mode").getAsString().toLowerCase(Locale.ROOT));
                context.waitFor(client -> ReleaseClient.settings().equals(previous), 600);
            }
        }
    }

    private static void configure(ClientGameTestContext context, Object renderer, Object config,
            float scale, boolean fsr, boolean linear) {
        context.runOnClient(client -> {
            set(config, "scale", scale);
            set(config, "fsr", fsr);
            set(config, "forceLinear", linear);
            set(config, "targetFrameRate", 0);
            call(renderer, "onResolutionChanged");
        });
        context.waitTicks(3);
    }

    private static void check(ClientGameTestContext context, Object renderer, Object config,
            float coordinateScale, float renderScale, boolean fsr, boolean dynamic,
            GpuFormat format, float strength, String scenario) {
        CompletableFuture<Void> precision = new CompletableFuture<>();
        Sample sample = context.computeOnClient(client -> {
            if (dynamic) {
                set(config, "targetFrameRate", 60);
                call(field(renderer, "dynamicScale"), "reset", new Class<?>[] {double.class}, 0.5);
                call(renderer, "resizeRenderTarget");
                if (((Number) field(config, "scale")).floatValue() != 1) {
                    throw new AssertionError("Dynamic fixture lost its configured ceiling");
                }
            }
            RenderTarget scaled = (RenderTarget) field(renderer, "renderTarget");
            RenderTarget main = client.gameRenderer.mainRenderTarget();
            if (main.getColorTexture().getFormat() != format
                    || scaled.width != Math.max((int) (main.width * renderScale), 1)
                    || scaled.height != Math.max((int) (main.height * renderScale), 1)
                    || scaled.getColorTexture().getFormat() != format) {
                throw new AssertionError("RenderScale dimensions or precision are incorrect");
            }
            var device = RenderSystem.getDevice();
            var encoder = device.createCommandEncoder();
            encoder.clearColorTexture(scaled.getColorTexture(),
                    new Vector4f(1.0f / 1024, 3.0f / 1024, 5.0f / 1024, 1));
            call(renderer, "blitAndBlendToTexture",
                    new Class<?>[] {RenderTarget.class, RenderTarget.class, FilterMode.class},
                    scaled, main, Boolean.TRUE.equals(call(config, "getFilter"))
                            ? FilterMode.LINEAR : FilterMode.NEAREST);
            if (fsr && ((RenderTarget) field(renderer, "fsrIntermediateTarget"))
                    .getColorTexture().getFormat() != format) {
                throw new AssertionError("FSR intermediate lost float precision");
            }
            var buffer = device.createBuffer(() -> "Release RenderScale precision readback", 9,
                    main.width * main.height * format.blockSize());
            encoder.copyTextureToBuffer(main.getColorTexture(), buffer, 0, () -> {
                try (var mapped = buffer.map(true, false)) {
                    var bytes = mapped.data().order(ByteOrder.nativeOrder());
                    float[] values = {1.0f / 1024, 3.0f / 1024, 5.0f / 1024, 1};
                    for (int i = 0; i < 4; i++) {
                        float value = format == GpuFormat.RGBA16_FLOAT
                                ? Float.float16ToFloat(bytes.getShort(i * 2)) : bytes.getFloat(i * 4);
                        // Preserve the original FSR bound: over 100 times smaller than an 8-bit step.
                        float tolerance = fsr ? 1.0f / 32768 : 0.00001f;
                        if (!Float.isFinite(value) || Math.abs(value - values[i]) > tolerance) {
                            throw new AssertionError("RenderScale blit quantized channel " + i + ": " + value);
                        }
                    }
                    precision.complete(null);
                } catch (Throwable failure) {
                    precision.completeExceptionally(failure);
                } finally {
                    buffer.close();
                }
            }, 0);
            encoder.clearColorTexture(main.getColorTexture(), new Vector4f(SOURCE, SOURCE, SOURCE, 1));
            long draws = ProcessedRenderObservations.draws();
            CompletableFuture<int[]> actual = capture(main, scenario);
            if (ProcessedRenderObservations.draws() != draws + 1) {
                throw new AssertionError("RenderScale screenshot did not execute the packaged dither pass");
            }
            GpuTextureView noise = ProcessedRenderObservations.lastDitherNoise();
            if (noise == null || noise.texture().isClosed()) {
                throw new AssertionError("RenderScale screenshot did not bind live noise");
            }
            int noiseWidth = noise.getWidth(0);
            int noiseHeight = noise.getHeight(0);
            CompletableFuture<int[]> noisePixels = readNoise(noise);
            if (ProcessedRenderObservations.draws() != draws + 1) {
                throw new AssertionError("Noise readback unexpectedly executed a dither pass");
            }
            return new Sample(main.width, main.height, noiseWidth, noiseHeight, actual, noisePixels);
        });
        context.waitFor(client -> sample.actual().isDone() && sample.noise().isDone()
                && precision.isDone(), 200);
        precision.join();
        assertPixels(sample, coordinateScale, strength, scenario);
    }

    private static CompletableFuture<int[]> readNoise(GpuTextureView noise) {
        TextureTarget target = new TextureTarget("Release RenderScale noise readback",
                noise.getWidth(0), noise.getHeight(0), GpuFormat.RGBA8_UNORM, null);
        CompletableFuture<int[]> result = new CompletableFuture<>();
        try {
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "Release RenderScale noise readback",
                            target.getColorTextureView(), java.util.Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("InSampler", noise,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            Screenshot.takeScreenshot(target, 1, image -> {
                try (image) {
                    if (image.getWidth() != target.width || image.getHeight() != target.height) {
                        throw new AssertionError("Unexpected noise readback dimensions");
                    }
                    result.complete(image.getPixels());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    target.destroyBuffers();
                }
            });
        } catch (Throwable failure) {
            target.destroyBuffers();
            result.completeExceptionally(failure);
        }
        return result;
    }

    private static CompletableFuture<int[]> capture(RenderTarget target, String scenario) {
        CompletableFuture<int[]> result = new CompletableFuture<>();
        Screenshot.takeScreenshot(target, 1, image -> {
            try (image) {
                if (image.getWidth() != target.width || image.getHeight() != target.height) {
                    throw new AssertionError("Unexpected RenderScale screenshot dimensions");
                }
                image.writeToFile(evidence(scenario, "actual"));
                result.complete(image.getPixels());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static void assertPixels(Sample sample, float scale, float strength, String scenario) {
        int[] actual = sample.actual().join();
        int[] noise = sample.noise().join();
        double source = SOURCE;
        int distinguish = 0;
        try (NativeImage expected = new NativeImage(sample.width(), sample.height(), false)) {
            for (int y = 0; y < sample.height(); y++) {
                int gpuY = sample.height() - 1 - y;
                for (int x = 0; x < sample.width(); x++) {
                    int pixel = expectedPixel(sample, noise, source, strength, x, gpuY, scale);
                    expected.setPixel(x, y, pixel);
                    if (pixel != expectedPixel(sample, noise, source, strength, x, gpuY,
                            scale == 1 ? 0.5f : 1)) distinguish++;
                }
            }
            expected.writeToFile(evidence(scenario, "expected"));
            for (int y = 0; y < sample.height(); y++) {
                for (int x = 0; x < sample.width(); x++) {
                    if (actual[y * sample.width() + x] != expected.getPixel(x, y)) {
                        throw new AssertionError("RenderScale screenshot used the wrong dither pixel grid at "
                                + x + "," + y + " scenario=" + scenario + " expected="
                                + Integer.toHexString(expected.getPixel(x, y)) + " actual="
                                + Integer.toHexString(actual[y * sample.width() + x]));
                    }
                }
            }
        } catch (IOException failure) {
            throw new AssertionError("Could not retain RenderScale CPU reference", failure);
        }
        if (distinguish == 0) {
            throw new AssertionError("RenderScale noise fixture did not distinguish coordinate scales");
        }
    }

    private static int expectedPixel(Sample sample, int[] noise, double source, float strength,
            int x, int gpuY, float scale) {
        int nx = DitherReference.noiseCoordinate(x, scale, sample.noiseWidth());
        int ny = DitherReference.noiseCoordinate(gpuY, scale, sample.noiseHeight());
        int noisePixel = noise[(sample.noiseHeight() - 1 - ny) * sample.noiseWidth() + nx];
        int pixel = 0xff000000;
        for (int channel = 0; channel < 3; channel++) {
            int shift = channel * 8;
            pixel |= DitherReference.channel(source, noisePixel >>> shift & 255,
                    strength, x, sample.width(), false) << shift;
        }
        return pixel;
    }

    private static Path evidence(String scenario, String kind) throws IOException {
        Path path = Path.of(System.getProperty("cbbg.test.evidence")).resolve("renderscale-"
                + System.getProperty("cbbg.test.backend") + "-" + scenario + "-" + kind + ".png");
        Files.createDirectories(path.getParent());
        return path;
    }

    private record Sample(int width, int height, int noiseWidth, int noiseHeight,
            CompletableFuture<int[]> actual, CompletableFuture<int[]> noise) {}
}
