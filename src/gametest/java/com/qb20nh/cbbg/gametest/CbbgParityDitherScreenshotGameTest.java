package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.debug.CbbgDebugState;
import com.qb20nh.cbbg.render.CbbgDither;
import com.qb20nh.cbbg.render.CbbgShaders;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Parity checks for: - dither + demo shaders producing expected per-pixel behavior - final-present
 * hook successfully using cbbg when mode is active - screenshots routed through the dithered RGBA8
 * output (enabled + demo)
 */
public class CbbgParityDitherScreenshotGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        CbbgConfig original = CbbgConfig.get();

        try {
            context.runOnClient(client -> {
                // Keep STBN generation small and deterministic for tests.
                CbbgConfig.setStbnSize(16);
                CbbgConfig.setStbnDepth(8);
                CbbgConfig.setStbnSeed(0L);
                CbbgConfig.setStrength(4.0f);

                CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA16F);
                CbbgConfig.setMode(CbbgConfig.Mode.ENABLED);

                CbbgDither.reloadStbn(true);
            });

            // Wait for shader reload + STBN generation completion.
            context.waitFor(client -> CbbgShaders.areReady() && !CbbgDither.isGenerating(), 400);

            // Execute the actual render/screenshot checks on the render thread.
            context.runOnClient(client -> {
                RenderSystem.assertOnRenderThread();

                // 1) Shader output behavior: dither output should not be uniform for uniform input.
                TextureTarget input = new TextureTarget(64, 64, false, Minecraft.ON_OSX);
                try {
                    input.setClearColor(0.501f, 0.501f, 0.501f, 1.0f);
                    input.clear(Minecraft.ON_OSX);

                    TextureTarget ditherOut = CbbgDither.renderDitheredTarget(input);
                    CbbgGameTestUtil.assertTrue(ditherOut != null,
                            "Expected non-null dither target output (shaders/STBN not ready?)");

                    try (NativeImage ditherImg = Screenshot.takeScreenshot(ditherOut)) {
                        int unique = uniqueRowColors(ditherImg, ditherImg.getHeight() / 2, 0,
                                ditherImg.getWidth());
                        CbbgGameTestUtil.assertTrue(unique > 1,
                                "Expected dithered output to vary across pixels (unique=" + unique
                                        + ")");
                    }

                    // 2) Demo split: left half should be uniform (strength=0), right half should
                    // vary.
                    TextureTarget demoOut = CbbgDither.renderDemoTarget(input);
                    CbbgGameTestUtil.assertTrue(demoOut != null,
                            "Expected non-null demo target output (shaders/STBN not ready?)");

                    try (NativeImage demoImg = Screenshot.takeScreenshot(demoOut)) {
                        int y = demoImg.getHeight() / 2;
                        int w = demoImg.getWidth();
                        int mid = w / 2;

                        // Exclude the 1px separator line at the split.
                        int leftUnique = uniqueRowColors(demoImg, y, 0, Math.max(0, mid - 1));
                        int rightUnique = uniqueRowColors(demoImg, y, Math.min(w, mid + 2), w);

                        CbbgGameTestUtil.assertTrue(leftUnique == 1,
                                "Expected demo left side to be uniform (unique=" + leftUnique
                                        + ")");
                        CbbgGameTestUtil.assertTrue(rightUnique > 1,
                                "Expected demo right side to vary (unique=" + rightUnique + ")");
                    }
                } finally {
                    input.destroyBuffers();
                }

                // 3) Final present hook: main.blitToScreen should use cbbg when enabled.
                CbbgDebugState.updatePresentUsedCbbg(false);
                RenderTarget main = client.getMainRenderTarget();
                main.blitToScreen(client.getWindow().getWidth(), client.getWindow().getHeight(),
                        true);
                CbbgGameTestUtil.assertTrue(CbbgDebugState.wasLastPresentUsedCbbg(),
                        "Expected cbbg to handle present when mode is ENABLED");

                // 4) Screenshot routing (enabled): screenshot of main should show dither variation.
                main.setClearColor(0.501f, 0.501f, 0.501f, 1.0f);
                main.clear(Minecraft.ON_OSX);
                try (NativeImage mainShot = Screenshot.takeScreenshot(main)) {
                    int unique = uniqueRowColors(mainShot, mainShot.getHeight() / 2, 0,
                            mainShot.getWidth());
                    CbbgGameTestUtil.assertTrue(unique > 1,
                            "Expected screenshot of main target to be dithered (unique=" + unique
                                    + ")");
                }

                // 5) Screenshot routing (demo): screenshot of main should show split behavior.
                CbbgConfig.setMode(CbbgConfig.Mode.DEMO);
                // Trigger the mode transition handler in the present mixin (resets dither state).
                main.blitToScreen(client.getWindow().getWidth(), client.getWindow().getHeight(),
                        true);

                main.setClearColor(0.501f, 0.501f, 0.501f, 1.0f);
                main.clear(Minecraft.ON_OSX);
                try (NativeImage demoShot = Screenshot.takeScreenshot(main)) {
                    int y = demoShot.getHeight() / 2;
                    int w = demoShot.getWidth();
                    int mid = w / 2;

                    int leftUnique = uniqueRowColors(demoShot, y, 0, Math.max(0, mid - 1));
                    int rightUnique = uniqueRowColors(demoShot, y, Math.min(w, mid + 2), w);

                    CbbgGameTestUtil.assertTrue(leftUnique == 1,
                            "Expected demo screenshot left side uniform (unique=" + leftUnique
                                    + ")");
                    CbbgGameTestUtil.assertTrue(rightUnique > 1,
                            "Expected demo screenshot right side varied (unique=" + rightUnique
                                    + ")");
                }
            });
        } finally {
            CbbgGameTestUtil.restoreConfig(original);
        }
    }

    private static int uniqueRowColors(NativeImage image, int y, int xStartInclusive,
            int xEndExclusive) {
        int w = image.getWidth();
        int x0 = Math.max(0, Math.min(w, xStartInclusive));
        int x1 = Math.max(0, Math.min(w, xEndExclusive));
        if (x1 <= x0) {
            return 0;
        }

        Set<Integer> colors = new HashSet<>();
        for (int x = x0; x < x1; x++) {
            colors.add(image.getPixelRGBA(x, y));
        }
        return colors.size();
    }
}
