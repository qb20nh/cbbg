package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import com.qb20nh.cbbg.render.stbn.STBNLoader;
import com.qb20nh.cbbg.render.stbn.StbnTextureManager;
import java.util.concurrent.CompletableFuture;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

public final class CbbgDither {

    private static final String S_IN = "InSampler";
    private static final String S_NOISE = "NoiseSampler";
    private static final String U_DITHER_INFO = "CbbgDitherInfo";
    private static final int DITHER_INFO_UBO_SIZE = 16;

    private static final @NonNull Identifier SCREENQUAD_VERTEX =
            (@NonNull Identifier) Identifier.withDefaultNamespace("core/screenquad");

    private static final @NonNull Identifier DITHER_SHADER = (@NonNull Identifier) Identifier
            .fromNamespaceAndPath(CbbgClient.MOD_ID, "core/cbbg_dither");
    private static final @NonNull Identifier DEMO_SHADER = (@NonNull Identifier) Identifier
            .fromNamespaceAndPath(CbbgClient.MOD_ID, "core/cbbg_demo");

    private static final @NonNull Identifier DITHER_PIPELINE_LOCATION =
            (@NonNull Identifier) Identifier.fromNamespaceAndPath(CbbgClient.MOD_ID,
                    "pipeline/cbbg_dither");
    private static final @NonNull Identifier DEMO_PIPELINE_LOCATION =
            (@NonNull Identifier) Identifier.fromNamespaceAndPath(CbbgClient.MOD_ID,
                    "pipeline/cbbg_demo");

    private static final @NonNull RenderPipeline DITHER_PIPELINE =
            (@NonNull RenderPipeline) RenderPipeline.builder()
                    .withLocation(DITHER_PIPELINE_LOCATION).withVertexShader(SCREENQUAD_VERTEX)
                    .withFragmentShader(DITHER_SHADER).withSampler(S_IN).withSampler(S_NOISE)
                    .withUniform(U_DITHER_INFO, UniformType.UNIFORM_BUFFER).withDepthWrite(false)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withoutBlend()
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                    .build();

    private static final @NonNull RenderPipeline DEMO_PIPELINE =
            (@NonNull RenderPipeline) RenderPipeline.builder().withLocation(DEMO_PIPELINE_LOCATION)
                    .withVertexShader(SCREENQUAD_VERTEX).withFragmentShader(DEMO_SHADER)
                    .withSampler(S_IN).withSampler(S_NOISE)
                    .withUniform(U_DITHER_INFO, UniformType.UNIFORM_BUFFER).withDepthWrite(false)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withoutBlend()
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                    .build();

    private static final AtomicBoolean loggedFailure = new AtomicBoolean(false);
    private static volatile boolean disabled = false;

    private static NativeImage[] stbnFrames;
    private static final StbnTextureManager stbnManager = new StbnTextureManager();
    private static int stbnFrameIndex = 0;
    private static volatile CompletableFuture<STBNGenerator.STBNFields> processedGeneration;
    private static MappableRingBuffer ditherInfoUbo;

    // Configurable state tracking
    private static boolean isGenerating = false;
    private static int currentWidth = 128;
    private static int currentHeight = 128;

    private static TextureTarget ditherTarget;

    private CbbgDither() {}

    public static void resetAfterToggle() {
        CbbgDither.initAsync();
        disabled = false;
        loggedFailure.set(false);
        // Texture recreation happens lazily in ensureGpuTargets via manager
    }

    public static boolean isDisabled() {
        return disabled;
    }

    public static int getStbnFrames() {
        if (stbnFrames != null) {
            return stbnFrames.length;
        }
        return CbbgConfig.get().stbnDepth();
    }

    /** 0..depth-1 (best-effort). */
    public static int getCurrentStbnFrameIndex() {
        int idx = stbnFrameIndex;
        if (idx <= 0) {
            return 0;
        }
        int depth = getStbnFrames();
        if (depth <= 0)
            return 0;
        return Math.floorMod(idx - 1, depth);
    }

    /**
     * Renders the dithering pass into an RGBA8 {@link TextureTarget} and returns it.
     *
     * <p>
     * This is used both for final presentation and for screenshots, so screenshots match the
     * dithered on-screen output.
     */
    public static TextureTarget renderDitheredTarget(GpuTextureView input) {
        return renderToTarget(input, DITHER_PIPELINE, DITHER_SHADER, "cbbg dither");
    }

    public static TextureTarget renderDemoTarget(GpuTextureView input) {
        return renderToTarget(input, DEMO_PIPELINE, DEMO_SHADER, "cbbg demo");
    }

    private static TextureTarget renderToTarget(GpuTextureView input,
            @NonNull RenderPipeline pipeline, @NonNull Identifier fragmentShader,
            String passLabel) {
        if (disabled) {
            return null;
        }

        try {
            RenderSystem.assertOnRenderThread();

            if (!CbbgClient.isEnabled()) {
                return null;
            }

            if (!areShadersReady(fragmentShader)) {
                return null;
            }

            final int width = input.getWidth(0);
            final int height = input.getHeight(0);
            if (width <= 0 || height <= 0) {
                return null;
            }

            ensureGpuTargets(width, height);

            GpuTextureView ditherView = ditherTarget.getColorTextureView();
            if (ditherView == null) {
                return null;
            }
            if (!stbnManager.isReady()) {
                return null;
            }

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            @NonNull
            GpuBuffer ditherInfo = ensureDitherInfoUbo(encoder);
            uploadStbnFrame(encoder);

            try (RenderPass pass = encoder.createRenderPass(() -> passLabel, ditherView,
                    Objects.requireNonNull(OptionalInt.empty()))) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform(U_DITHER_INFO, ditherInfo);

                pass.bindTexture(S_IN, input,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.bindTexture(S_NOISE, stbnManager.getView(),
                        RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
                pass.draw(0, 3);
            }
            if (ditherInfoUbo != null) {
                ditherInfoUbo.rotate();
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

        stbnManager.ensureTexture(currentWidth, currentHeight);
    }

    private static void uploadStbnFrame(CommandEncoder encoder) {
        if (stbnFrames == null || stbnFrames.length == 0) {
            return;
        }

        int idx = stbnFrameIndex++ % stbnFrames.length;
        NativeImage frame = stbnFrames[idx];
        GpuTexture texture = stbnManager.getTexture();
        if (frame == null || texture == null) {
            return;
        }

        encoder.writeToTexture(texture, frame);
    }

    public static void initAsync() {
        CbbgConfig cfg = CbbgConfig.get();
        STBNGenerator.generateAsync(cfg.stbnSize(), cfg.stbnSize(), cfg.stbnDepth(),
                cfg.stbnSeed());
    }

    public static void reloadStbn(boolean force) {
        if (isGenerating && !force)
            return;

        CbbgConfig cfg = CbbgConfig.get();
        if (force) {
            // Clear cache for these params implies we want fresh ones
            STBNLoader.clearCacheExceptDefaults();

            // Start generation
            STBNGenerator.generateAsync(cfg.stbnSize(), cfg.stbnSize(), cfg.stbnDepth(),
                    cfg.stbnSeed());
            isGenerating = true;

            // Notify
            if (cfg.notifyChat() && Minecraft.getInstance().level != null) {
                Minecraft.getInstance().gui.getChat()
                        .addMessage(Component.literal("§e[cbbg] Generating new STBN textures..."));
            }
            if (cfg.notifyToast()) {
                SystemToast.add(Minecraft.getInstance().getToastManager(),
                        SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.literal("cbbg Generation"),
                        Component.literal("Generating noise textures..."));
            }

        } else {
            initAsync();
        }
    }

    public static boolean isGenerating() {
        return isGenerating;
    }

    public static void ensureStbnLoaded() {
        CompletableFuture<STBNGenerator.STBNFields> pendingGen = STBNGenerator.get();
        if (pendingGen != null && pendingGen.isDone() && !pendingGen.isCancelled()
                && pendingGen != processedGeneration) {
            processedGeneration = pendingGen;
            try {
                STBNGenerator.STBNFields fields = pendingGen.join(); // Should be immediate
                onStbnGenerationComplete(fields);
            } catch (Exception e) {
                CbbgClient.LOGGER.error("Failed to retrieve STBN fields", e);
            }
        } else if (stbnFrames == null && (pendingGen == null || pendingGen.isCancelled())) {
            initAsync();
        }
    }

    private static void onStbnGenerationComplete(STBNGenerator.STBNFields fields) {
        CbbgConfig cfg = CbbgConfig.get();
        stbnFrames =
                STBNLoader.loadOrGenerate(cfg.stbnSize(), cfg.stbnSize(), cfg.stbnDepth(), fields);

        // Update state dims
        if (stbnFrames != null && stbnFrames.length > 0) {
            currentWidth = stbnFrames[0].getWidth();
            currentHeight = stbnFrames[0].getHeight();
        }

        if (isGenerating) {
            isGenerating = false;
            // Only notify chat if IN-GAME
            if (cfg.notifyChat() && Minecraft.getInstance().level != null) {
                Minecraft.getInstance().gui.getChat()
                        .addMessage(Component.literal("§a[cbbg] STBN Generation Complete!"));
            }

        }
    }

    private static boolean areShadersReady(@NonNull Identifier fragmentShader) {
        Minecraft mc = Minecraft.getInstance();
        ShaderManager shaderManager = mc.getShaderManager();

        // Only query existence; compiling happens later when we actually use the
        // pipeline.
        if (shaderManager.getShader(SCREENQUAD_VERTEX, ShaderType.VERTEX) == null) {
            return false;
        }
        return shaderManager.getShader(fragmentShader, ShaderType.FRAGMENT) != null;
    }

    private static @NonNull GpuBuffer ensureDitherInfoUbo(CommandEncoder encoder) {
        if (ditherInfoUbo == null) {
            ditherInfoUbo = new MappableRingBuffer(() -> "cbbg / DitherInfo",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, DITHER_INFO_UBO_SIZE);
        }

        GpuBuffer buffer = ditherInfoUbo.currentBuffer();
        float strength = CbbgConfig.get().strength();
        float coordScale = RenderScaleCompat.getDitherCoordScale();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(buffer, false, true)) {
            Std140Builder.intoBuffer(view.data()).putFloat(strength).putVec2(coordScale,
                    coordScale);
        }
        return buffer;
    }

    private static void disableWithLog(Exception e) {
        if (loggedFailure.compareAndSet(false, true)) {
            CbbgClient.LOGGER.error("Disabling cbbg due to rendering error", e);
            disabled = true;
        }
    }
}
