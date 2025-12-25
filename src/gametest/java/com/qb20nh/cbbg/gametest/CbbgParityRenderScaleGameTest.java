package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.debug.CbbgDebugState;
import com.qb20nh.cbbg.render.CbbgDither;
import com.qb20nh.cbbg.render.CbbgShaders;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.opengl.GL30;

/**
 * RenderScale compatibility parity: - when RenderScale is active (scale < 1), cbbg upgrades
 * RenderScale's intermediate color target to float - dither grid aligns to RenderScale's pixel grid
 * (pairs of pixels share the same noise sample when scale=0.5)
 */
public class CbbgParityRenderScaleGameTest implements FabricClientGameTest {

    private static final float TEST_SCALE = 0.5f;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!FabricLoader.getInstance().isModLoaded("renderscale")) {
            throw new AssertionError(
                    "RenderScale mod is not loaded; expected it on the gametest runtime classpath");
        }

        CbbgConfig original = CbbgConfig.get();
        float originalRenderScale = getRenderScaleConfigScale();

        try {
            context.runOnClient(client -> {
                // Make sure cbbg is active and has its shaders + STBN ready.
                CbbgConfig.setMode(CbbgConfig.Mode.ENABLED);
                CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA16F);
                CbbgConfig.setStbnSize(16);
                CbbgConfig.setStbnDepth(8);
                CbbgConfig.setStbnSeed(0L);
                CbbgConfig.setStrength(4.0f);
                CbbgDither.reloadStbn(true);

                // Enable RenderScale downscaling and trigger its resize hook.
                setRenderScaleConfigScale(TEST_SCALE);
                invokeRenderScaleOnResolutionChanged();

                // Clear captured values before we force any new allocations.
                CbbgDebugState.updateRenderScaleInternalFormat(-1);
            });

            context.waitFor(client -> CbbgShaders.areReady() && !CbbgDither.isGenerating(), 400);
            context.waitTicks(5);

            // Force at least one render-target resize path to run while RenderScale is active.
            context.runOnClient(client -> {
                RenderSystem.assertOnRenderThread();
                RenderTarget main = client.getMainRenderTarget();
                main.blitToScreen(client.getWindow().getWidth(), client.getWindow().getHeight(),
                        true);
            });
            context.waitTicks(2);

            int renderScaleInternal = context
                    .computeOnClient(client -> CbbgDebugState.getRenderScaleInternalFormat());
            if (renderScaleInternal != -1) {
                boolean isFloat = renderScaleInternal == GL30.GL_RGBA16F
                        || renderScaleInternal == GL30.GL_RGBA32F;
                CbbgGameTestUtil.assertTrue(isFloat,
                        "Expected RenderScale intermediate target to be float while cbbg active; got internal="
                                + renderScaleInternal);
            } else {
                // If RenderScale didn't allocate any intermediate targets on this code path,
                // at least assert that the coordScale plumbing is correct (this is the core logic
                // cbbg controls directly).
                float coordScale = context.computeOnClient(
                        client -> com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat
                                .getDitherCoordScale());
                CbbgGameTestUtil.assertTrue(Math.abs(coordScale - TEST_SCALE) < 1e-4f,
                        "Expected RenderScaleCompat.getDitherCoordScale() ~= " + TEST_SCALE
                                + ", got " + coordScale);
            }

            // Dither-grid alignment check: with coordScale=0.5, pixels should repeat in 2x2 blocks.
            context.runOnClient(client -> {
                RenderSystem.assertOnRenderThread();

                TextureTarget input = new TextureTarget(64, 64, false, Minecraft.ON_OSX);
                try {
                    input.setClearColor(0.501f, 0.501f, 0.501f, 1.0f);
                    input.clear(Minecraft.ON_OSX);

                    TextureTarget out = CbbgDither.renderDitheredTarget(input);
                    CbbgGameTestUtil.assertTrue(out != null, "Expected dither output target");

                    try (NativeImage img = Screenshot.takeScreenshot(out)) {
                        assertRepeatsInPairs(img);
                    }
                } finally {
                    input.destroyBuffers();
                }
            });
        } finally {
            // Restore RenderScale first (so future tests won't see an unexpected scale).
            try {
                setRenderScaleConfigScale(originalRenderScale);
                invokeRenderScaleOnResolutionChanged();
            } catch (Throwable ignored) {
                // If RenderScale API changes, we still restore cbbg config.
            }
            CbbgGameTestUtil.restoreConfig(original);
        }
    }

    private static void assertRepeatsInPairs(NativeImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int maxX = (w / 2) * 2; // even
        int maxY = (h / 2) * 2; // even

        // Sample a few rows/cols (enough to validate the grid without being too slow).
        for (int y = 0; y < maxY; y += Math.max(2, h / 8)) {
            int yy = (y / 2) * 2;
            for (int x = 0; x < maxX; x += 2) {
                int c00 = img.getPixelRGBA(x, yy);
                int c10 = img.getPixelRGBA(x + 1, yy);
                int c01 = img.getPixelRGBA(x, yy + 1);
                int c11 = img.getPixelRGBA(x + 1, yy + 1);

                if (c00 != c10 || c00 != c01 || c00 != c11) {
                    throw new AssertionError(String.format(Locale.ROOT,
                            "Expected 2x2 block to be identical at (%d,%d) for RenderScale grid alignment",
                            x, yy));
                }
            }
        }
    }

    private static float getRenderScaleConfigScale() {
        try {
            Object cfg = Class.forName("dev.zelo.renderscale.CommonClass").getMethod("getConfig")
                    .invoke(null);
            Object v = cfg.getClass().getMethod("getScale").invoke(cfg);
            if (v instanceof Number n) {
                return n.floatValue();
            }
            return 1.0f;
        } catch (Exception e) {
            throw new AssertionError("Failed to read RenderScale config scale via reflection", e);
        }
    }

    private static void setRenderScaleConfigScale(float scale) {
        try {
            Object cfg = Class.forName("dev.zelo.renderscale.CommonClass").getMethod("getConfig")
                    .invoke(null);
            cfg.getClass().getField("scale").setFloat(cfg, scale);
        } catch (Exception e) {
            throw new AssertionError("Failed to set RenderScale config scale via reflection", e);
        }
    }

    private static void invokeRenderScaleOnResolutionChanged() {
        try {
            Object inst = Class.forName("dev.zelo.renderscale.CommonClass").getMethod("getInstance")
                    .invoke(null);
            inst.getClass().getMethod("onResolutionChanged").invoke(inst);
        } catch (Exception e) {
            throw new AssertionError("Failed to invoke RenderScale onResolutionChanged()", e);
        }
    }
}
