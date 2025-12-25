package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import com.qb20nh.cbbg.render.stbn.STBNLoader;
import com.qb20nh.cbbg.render.stbn.StbnTextureManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.network.chat.Component;

public final class CbbgDither {

    private static final String S_IN = "InSampler";
    private static final String S_NOISE = "NoiseSampler";
    private static final String U_STRENGTH = "Strength";
    private static final String U_COORD_SCALE = "CoordScale";

    private static final AtomicBoolean loggedFailure = new AtomicBoolean(false);
    private static volatile boolean disabled = false;

    private static NativeImage[] stbnFrames;
    private static final StbnTextureManager stbnManager = new StbnTextureManager();
    private static int stbnFrameIndex = 0;
    private static volatile CompletableFuture<STBNGenerator.STBNFields> processedGeneration;

    // Configurable state tracking
    private static boolean isGenerating = false;
    private static int currentWidth = 128;
    private static int currentHeight = 128;

    // RGBA8 output target (used for both on-screen present and screenshots)
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
        if (depth <= 0) {
            return 0;
        }
        return Math.floorMod(idx - 1, depth);
    }

    /**
     * Renders the dithering pass into an RGBA8 {@link TextureTarget} and returns it.
     *
     * <p>
     * This is used both for final presentation and for screenshots, so screenshots match the
     * dithered on-screen output.
     */
    public static TextureTarget renderDitheredTarget(RenderTarget input) {
        return renderToTarget(input, CbbgShaders.getDither(), "cbbg dither");
    }

    public static TextureTarget renderDemoTarget(RenderTarget input) {
        return renderToTarget(input, CbbgShaders.getDemo(), "cbbg demo");
    }

    private static TextureTarget renderToTarget(RenderTarget input, ShaderInstance shader,
            String passLabel) {
        if (disabled) {
            return null;
        }
        try {
            RenderSystem.assertOnRenderThread();

            if (!CbbgClient.isEnabled()) {
                return null;
            }

            if (shader == null) {
                return null;
            }

            if (input == null) {
                return null;
            }

            if (stbnFrames == null || stbnFrames.length == 0) {
                return null;
            }

            int width = input.width;
            int height = input.height;
            if (width <= 0 || height <= 0) {
                return null;
            }

            ensureTargets(width, height);
            if (ditherTarget == null) {
                return null;
            }

            if (!stbnManager.isReady()) {
                return null;
            }

            uploadStbnFrame();

            int noiseTex = stbnManager.getTextureId();
            if (noiseTex <= 0) {
                return null;
            }

            // Bind samplers and update uniforms.
            shader.setSampler(S_IN, input);
            shader.setSampler(S_NOISE, noiseTex);

            float strength = getEffectiveStrength();
            float coordScale = RenderScaleCompat.getDitherCoordScale();
            shader.safeGetUniform(U_STRENGTH).set(strength);
            shader.safeGetUniform(U_COORD_SCALE).set(coordScale, coordScale);

            // Render into RGBA8 output target.
            input.unbindWrite();
            shader.apply();

            ditherTarget.clear(Minecraft.ON_OSX);
            ditherTarget.bindWrite(false);

            RenderSystem.viewport(0, 0, width, height);
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._disableBlend();

            BufferBuilder bufferBuilder = RenderSystem.renderThreadTesselator()
                    .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
            bufferBuilder.addVertex(0.0F, 0.0F, 0.0F);
            bufferBuilder.addVertex(1.0F, 0.0F, 0.0F);
            bufferBuilder.addVertex(1.0F, 1.0F, 0.0F);
            bufferBuilder.addVertex(0.0F, 1.0F, 0.0F);
            BufferUploader.draw(bufferBuilder.buildOrThrow());

            shader.clear();

            GlStateManager._depthMask(true);
            ditherTarget.unbindWrite();

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
    public static boolean blitToScreenWithDither(RenderTarget input) {
        CbbgConfig.Mode mode = CbbgClient.getEffectiveMode();
        // Strict: do not delegate to other blit methods based on a fresh mode read.
        // Mode may change concurrently (UI thread) while we are presenting; returning false falls
        // back to vanilla for this frame and avoids any mutual recursion.
        if (mode != CbbgConfig.Mode.ENABLED) {
            return false;
        }

        try {
            RenderSystem.assertOnRenderThread();

            TextureTarget out = renderDitheredTarget(input);
            if (out == null) {
                return false;
            }

            Minecraft mc = Minecraft.getInstance();
            out.blitToScreen(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            return true;
        } catch (Exception e) {
            disableWithLog(e);
            return false;
        }
    }

    public static boolean blitToScreenWithDemo(RenderTarget input) {
        CbbgConfig.Mode mode = CbbgClient.getEffectiveMode();
        // Strict: see note in blitToScreenWithDither().
        if (mode != CbbgConfig.Mode.DEMO) {
            return false;
        }

        try {
            RenderSystem.assertOnRenderThread();

            TextureTarget out = renderDemoTarget(input);
            if (out == null) {
                return false;
            }

            Minecraft mc = Minecraft.getInstance();
            out.blitToScreen(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            return true;
        } catch (Exception e) {
            disableWithLog(e);
            return false;
        }
    }

    private static void ensureTargets(int width, int height) {
        if (ditherTarget == null || ditherTarget.width != width || ditherTarget.height != height) {
            if (ditherTarget == null) {
                ditherTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            } else {
                ditherTarget.resize(width, height, Minecraft.ON_OSX);
            }
        }

        stbnManager.ensureTexture(currentWidth, currentHeight);
    }

    private static void uploadStbnFrame() {
        if (stbnFrames == null || stbnFrames.length == 0) {
            return;
        }
        int idx = stbnFrameIndex++ % stbnFrames.length;
        NativeImage frame = stbnFrames[idx];
        if (frame == null) {
            return;
        }
        stbnManager.uploadFrame(frame);
    }

    public static void initAsync() {
        CbbgConfig cfg = CbbgConfig.get();
        STBNGenerator.generateAsync(cfg.stbnSize(), cfg.stbnSize(), cfg.stbnDepth(),
                cfg.stbnSeed());
    }

    public static void reloadStbn(boolean force) {
        if (isGenerating && !force) {
            return;
        }

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
                        .addMessage(Component.translatable("cbbg.chat.stbn.generating")
                                .withStyle(ChatFormatting.YELLOW));
            }
            if (cfg.notifyToast()) {
                SystemToast.addOrUpdate(Minecraft.getInstance().getToasts(),
                        SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.translatable("cbbg.toast.stbn.title"),
                        Component.translatable("cbbg.toast.stbn.generating"));
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
                Cbbg.LOGGER.error("Failed to retrieve STBN fields", e);
            }
        } else if (stbnFrames == null && (pendingGen == null || pendingGen.isCancelled())) {
            initAsync();
        }
    }

    private static void onStbnGenerationComplete(STBNGenerator.STBNFields fields) {
        CbbgConfig cfg = CbbgConfig.get();

        // If the config changed while a generation was running, discard the result rather than
        // attempting to interpret arrays with mismatched dimensions (can crash).
        if (fields != null) {
            int expectedW = cfg.stbnSize();
            int expectedH = cfg.stbnSize();
            int expectedD = cfg.stbnDepth();
            long expectedSeed = cfg.stbnSeed();
            if (fields.width() != expectedW || fields.height() != expectedH
                    || fields.depth() != expectedD || fields.seed() != expectedSeed) {
                Cbbg.LOGGER.info(
                        "STBN generation finished for {}x{}x{} seed={}, but config is {}x{}x{} seed={}; restarting generation.",
                        fields.width(), fields.height(), fields.depth(), fields.seed(), expectedW,
                        expectedH, expectedD, expectedSeed);
                initAsync();
                return;
            }
        }

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
                Minecraft.getInstance().gui.getChat().addMessage(Component
                        .translatable("cbbg.chat.stbn.complete").withStyle(ChatFormatting.GREEN));
            }
            if (cfg.notifyToast()) {
                SystemToast.addOrUpdate(Minecraft.getInstance().getToasts(),
                        SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.translatable("cbbg.toast.stbn.title"),
                        Component.translatable("cbbg.toast.stbn.complete"));
            }
        }
    }

    private static float getEffectiveStrength() {
        float base = CbbgConfig.get().strength();

        // The pause/menu background blur produces very smooth gradients, which makes 8-bit output
        // banding much more obvious. Increasing dither strength only for menu-style screens keeps
        // gameplay noise unchanged while improving the blurred background.
        //
        // --- ImmediatelyFast compat: do not remove ---
        // This is purely a shader uniform tweak; it does not allocate textures or touch framebuffer
        // bindings, and should not interfere with ImmediatelyFast's render optimizations.
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) {
            return base;
        }
        if (!screen.isPauseScreen()) {
            return base;
        }

        int blur = mc.options.getMenuBackgroundBlurriness();
        if (blur < 1) {
            return base;
        }

        // Scale with the configured blur radius: default blur (5) becomes ~2x strength.
        float multiplier = 1.0f + (blur / 5.0f);
        float boosted = base * multiplier;
        return Math.min(4.0f, Math.max(0.5f, boosted));
    }

    private static void disableWithLog(Exception e) {
        if (loggedFailure.compareAndSet(false, true)) {
            Cbbg.LOGGER.error("Disabling cbbg due to rendering error", e);
            disabled = true;
        }
    }
}
