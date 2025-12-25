package com.qb20nh.cbbg.gametest;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.debug.CbbgDebugState;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Parity checks for menu background blur intermediates: - when cbbg is enabled, blur post chain
 * targets allocate float internal formats - resizing (which forces PostChain target recreation)
 * still allocates float while active
 */
public class CbbgParityMenuBlurGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        CbbgConfig original = CbbgConfig.get();

        try {
            context.runOnClient(client -> {
                CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA16F);
                CbbgConfig.setMode(CbbgConfig.Mode.ENABLED);

                // Clear previous captured values.
                CbbgDebugState.updateBlurInternalFormat(-1);

                // Force blur chain targets to recreate via PostChain.resize (called from
                // GameRenderer.resize).
                client.gameRenderer.resize(client.getWindow().getWidth(),
                        client.getWindow().getHeight());
            });
            context.waitTicks(2);

            context.runOnClient(client -> {
                int blurInternal = CbbgDebugState.getBlurInternalFormat();
                CbbgGameTestUtil.assertTrue(blurInternal != -1,
                        "Expected blur target internal format to be captured");

                CbbgConfig.PixelFormat desired =
                        MainTargetFormatSupport.getEffective(CbbgConfig.get().pixelFormat());
                if (desired == CbbgConfig.PixelFormat.RGBA8) {
                    CbbgGameTestUtil.assertEquals(GL11.GL_RGBA8, blurInternal,
                            "Blur target internal format when no float formats are supported");
                } else {
                    boolean isFloat =
                            blurInternal == GL30.GL_RGBA16F || blurInternal == GL30.GL_RGBA32F;
                    CbbgGameTestUtil.assertTrue(isFloat,
                            "Expected blur target to be float while cbbg active; got internal="
                                    + blurInternal);
                }

                long prevUpdate = CbbgDebugState.getBlurLastUpdateNanos();

                int w2 = Math.max(1, client.getWindow().getWidth() - 1);
                int h2 = Math.max(1, client.getWindow().getHeight() - 1);
                client.gameRenderer.resize(w2, h2);

                CbbgGameTestUtil.assertTrue(CbbgDebugState.getBlurLastUpdateNanos() != prevUpdate,
                        "Expected blur target recreation to update blur debug timestamp");
            });
        } finally {
            CbbgGameTestUtil.restoreConfig(original);
        }
    }
}
