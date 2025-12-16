package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import static java.util.Objects.requireNonNull;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;

public final class CbbgDither {

    private static final String S_IN = "InSampler";
    private static final String S_NOISE = "NoiseSampler";

    private static final Identifier SCREENQUAD_VERTEX =
            requireNonNull(Identifier.withDefaultNamespace("core/screenquad"));

    private static final Identifier DITHER_SHADER =
            requireNonNull(Identifier.fromNamespaceAndPath(CbbgClient.MOD_ID, "core/cbbg_dither"));
    private static final Identifier DEMO_SHADER =
            requireNonNull(Identifier.fromNamespaceAndPath(CbbgClient.MOD_ID, "core/cbbg_demo"));

    private static final RenderPipeline DITHER_PIPELINE = RenderPipeline.builder()
            .withLocation(requireNonNull(
                    Identifier.fromNamespaceAndPath(CbbgClient.MOD_ID, "pipeline/cbbg_dither")))
            .withVertexShader(requireNonNull(SCREENQUAD_VERTEX))
            .withFragmentShader(requireNonNull(DITHER_SHADER)).withSampler(S_IN)
            .withSampler(S_NOISE).withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withoutBlend()
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build();

    private static final RenderPipeline DEMO_PIPELINE = RenderPipeline.builder()
            .withLocation(requireNonNull(
                    Identifier.fromNamespaceAndPath(CbbgClient.MOD_ID, "pipeline/cbbg_demo")))
            .withVertexShader(requireNonNull(SCREENQUAD_VERTEX))
            .withFragmentShader(requireNonNull(DEMO_SHADER)).withSampler(S_IN).withSampler(S_NOISE)
            .withDepthWrite(false).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withoutBlend().withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build();

    private static final AtomicBoolean loggedFailure = new AtomicBoolean(false);
    private static volatile boolean disabled = false;

    private static NativeImage[] stbnFrames;
    private static GpuTexture stbnTexture;
    private static GpuTextureView stbnTextureView;
    private static int stbnFrameIndex = 0;

    private static TextureTarget ditherTarget;

    private CbbgDither() {}

    public static void resetAfterToggle() {
        CbbgDither.initAsync();
        disabled = false;
        loggedFailure.set(false);
    }

    public static boolean isDisabled() {
        return disabled;
    }

    public static int getStbnFrames() {
        return STBNGenerator.STBN_FRAMES;
    }

    /** 0..STBNGenerator.STBN_FRAMES-1 (best-effort). */
    public static int getCurrentStbnFrameIndex() {
        int idx = stbnFrameIndex;
        if (idx <= 0) {
            return 0;
        }
        return Math.floorMod(idx - 1, STBNGenerator.STBN_FRAMES);
    }

    /**
     * Renders the dithering pass into an RGBA8 {@link TextureTarget} and returns it.
     *
     * <p>
     * This is used both for final presentation and for screenshots, so screenshots match the
     * dithered on-screen output.
     */
    public static TextureTarget renderDitheredTarget(GpuTextureView input) {
        return renderToTarget(input, DITHER_PIPELINE, "cbbg dither");
    }

    public static TextureTarget renderDemoTarget(GpuTextureView input) {
        return renderToTarget(input, DEMO_PIPELINE, "cbbg demo");
    }

    private static TextureTarget renderToTarget(GpuTextureView input, RenderPipeline pipeline,
            String passLabel) {
        if (disabled) {
            return null;
        }

        try {
            RenderSystem.assertOnRenderThread();

            if (!CbbgClient.isEnabled()) {
                return null;
            }

            if (!areShadersReadyForMode(pipeline == DEMO_PIPELINE)) {
                return null;
            }

            final int width = input.getWidth(0);
            final int height = input.getHeight(0);
            if (width <= 0 || height <= 0) {
                return null;
            }

            ensureStbnLoaded();
            ensureGpuTargets(width, height);

            GpuTextureView ditherView = ditherTarget.getColorTextureView();
            if (ditherView == null) {
                return null;
            }
            if (stbnTextureView == null) {
                return null;
            }

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            uploadStbnFrame(encoder);

            try (RenderPass pass = encoder.createRenderPass(() -> passLabel, ditherView,
                    requireNonNull(OptionalInt.empty()))) {
                pass.setPipeline(requireNonNull(pipeline));
                RenderSystem.bindDefaultUniforms(pass);
                pass.bindTexture(S_IN, input,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.bindTexture(S_NOISE, stbnTextureView,
                        RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
                pass.draw(0, 3);
            }

            return ditherTarget;
        } catch (Exception e) {
            disableWithLog(e);
            return null;
        }
    }

    /**
     * @return true if cbbg performed the blit/present itself (caller should cancel vanilla),
     *         otherwise false to fall back to vanilla.
     */
    public static boolean blitToScreenWithDither(GpuTextureView input) {
        CbbgConfig.Mode mode = CbbgClient.getEffectiveMode();
        // Strict: do not delegate to other blit methods based on a fresh mode read.
        // Mode may change concurrently (UI thread) while we are presenting; returning
        // false falls back to
        // vanilla for this frame and avoids any mutual recursion.
        if (mode != CbbgConfig.Mode.ENABLED) {
            return false;
        }

        try {
            RenderSystem.assertOnRenderThread();

            TextureTarget out = renderDitheredTarget(input);
            if (out == null || out.getColorTextureView() == null) {
                return false;
            }

            GpuTextureView outView = out.getColorTextureView();
            if (outView == null) {
                return false;
            }
            RenderSystem.getDevice().createCommandEncoder().presentTexture(outView);
            return true;
        } catch (Exception e) {
            disableWithLog(e);
            return false;
        }
    }

    public static boolean blitToScreenWithDemo(GpuTextureView input) {
        CbbgConfig.Mode mode = CbbgClient.getEffectiveMode();
        // Strict: see note in blitToScreenWithDither().
        if (mode != CbbgConfig.Mode.DEMO) {
            return false;
        }

        try {
            RenderSystem.assertOnRenderThread();
            TextureTarget out = renderDemoTarget(input);
            if (out == null || out.getColorTextureView() == null) {
                return false;
            }
            GpuTextureView outView = out.getColorTextureView();
            if (outView == null) {
                return false;
            }
            RenderSystem.getDevice().createCommandEncoder().presentTexture(outView);
            return true;
        } catch (Exception e) {
            disableWithLog(e);
            return false;
        }
    }

    private static void ensureGpuTargets(int width, int height) {
        if (ditherTarget == null || ditherTarget.width != width || ditherTarget.height != height) {
            if (ditherTarget == null) {
                ditherTarget = new TextureTarget("cbbg / Dither Output", width, height, false);
            } else {
                ditherTarget.resize(width, height);
            }
        }

        if (stbnTexture == null || stbnTexture.isClosed() || stbnTextureView == null
                || stbnTextureView.isClosed()) {
            if (stbnTexture == null || stbnTexture.isClosed()) {
                stbnTexture = RenderSystem.getDevice().createTexture(() -> "cbbg / STBN",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        TextureFormat.RGBA8, STBNGenerator.STBN_SIZE, STBNGenerator.STBN_SIZE, 1,
                        1);
            }
            stbnTextureView =
                    RenderSystem.getDevice().createTextureView(requireNonNull(stbnTexture));
        }
    }

    private static void uploadStbnFrame(CommandEncoder encoder) {
        if (stbnFrames == null || stbnFrames.length != STBNGenerator.STBN_FRAMES) {
            return;
        }

        int idx = stbnFrameIndex++ % STBNGenerator.STBN_FRAMES;
        NativeImage frame = stbnFrames[idx];
        if (frame == null || stbnTexture == null) {
            return;
        }

        encoder.writeToTexture(requireNonNull(stbnTexture), frame);
    }

    public static void initAsync() {
        STBNGenerator.init(); // Starts pure Math generation mostly just for PrePrePreLaunch
    }

    private static void ensureStbnLoaded() {
        if (stbnFrames != null) {
            return;
        }

        var pendingGen = STBNGenerator.get();

        if (pendingGen != null) {
            // We block here if needed because we are about to Render.
            // Ideally this is fast or already done.
            try {
                var fields = pendingGen.join();
                stbnFrames = STBNLoader.loadOrGenerate(STBNGenerator.STBN_SIZE,
                        STBNGenerator.STBN_SIZE, STBNGenerator.STBN_FRAMES, fields);
            } catch (Exception e) {
                CbbgClient.LOGGER.error("Failed to retrieve STBN fields", e);
            }
        }
    }

    private static boolean areShadersReadyForMode(boolean demo) {
        Minecraft mc = Minecraft.getInstance();
        ShaderManager shaderManager = mc.getShaderManager();

        // Only query existence; compiling happens later when we actually use the
        // pipeline.
        if (shaderManager.getShader(requireNonNull(SCREENQUAD_VERTEX), ShaderType.VERTEX) == null) {
            return false;
        }
        if (demo) {
            return shaderManager.getShader(requireNonNull(DEMO_SHADER),
                    ShaderType.FRAGMENT) != null;
        }
        return shaderManager.getShader(requireNonNull(DITHER_SHADER), ShaderType.FRAGMENT) != null;
    }

    private static void disableWithLog(Throwable t) {
        disabled = true;
        if (loggedFailure.compareAndSet(false, true)) {
            CbbgClient.LOGGER.warn(
                    "Disabling cbbg dithering due to an error; falling back to vanilla blit.", t);
        }
    }
}
