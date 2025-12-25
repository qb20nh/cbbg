package com.qb20nh.cbbg.gametest;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.debug.CbbgGlUtil;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import java.util.ArrayList;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.lwjgl.opengl.GL11;

/**
 * Parity checks that don't require a world: - main render target internal format tracks the
 * requested pixel format (with runtime fallback) - F3 debug overlay includes cbbg verification
 * lines - disabling cbbg and forcing a resize returns the main target to RGBA8
 */
public class CbbgParityMainTargetGameTest implements FabricClientGameTest {

        @Override
        public void runTest(ClientGameTestContext context) {
                CbbgConfig original = CbbgConfig.get();

                try {
                        // Request the highest format (RGBA32F) so the runtime fallback chain is
                        // exercised if
                        // the driver can't support it.
                        context.runOnClient(client -> {
                                CbbgConfig.setMode(CbbgConfig.Mode.ENABLED);
                                CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA32F);

                                client.getMainRenderTarget().resize(client.getWindow().getWidth(),
                                                client.getWindow().getHeight(), Minecraft.ON_OSX);
                        });
                        context.waitTicks(2);

                        CbbgConfig.PixelFormat effective = context.computeOnClient(
                                        client -> MainTargetFormatSupport.getEffective(
                                                        CbbgConfig.PixelFormat.RGBA32F));
                        int expectedInternal = CbbgGameTestUtil.glInternalFor(effective);

                        int actualInternal = context.computeOnClient(client -> CbbgGlUtil
                                        .getTextureInternalFormat2D(client.getMainRenderTarget()
                                                        .getColorTextureId()));

                        CbbgGameTestUtil.assertEquals(expectedInternal, actualInternal,
                                        "MainTarget internal format (enabled, effective="
                                                        + effective + ")");

                        // Debug overlay must include cbbg lines (even if F3 isn't currently shown).
                        List<String> lines = context.computeOnClient(
                                        client -> getGameInfoLines(client.gui.getDebugOverlay()));

                        boolean hasModeLine =
                                        lines.stream().anyMatch(s -> s.startsWith("cbbg: mode="));
                        boolean hasFmtLine =
                                        lines.stream().anyMatch(s -> s.startsWith("cbbg: main="));
                        CbbgGameTestUtil.assertTrue(hasModeLine,
                                        "Missing debug overlay line starting with 'cbbg: mode='");
                        CbbgGameTestUtil.assertTrue(hasFmtLine,
                                        "Missing debug overlay line starting with 'cbbg: main='");

                        // Now disable and force a resize: vanilla should allocate RGBA8.
                        context.runOnClient(client -> {
                                CbbgConfig.setMode(CbbgConfig.Mode.DISABLED);
                                client.getMainRenderTarget().resize(client.getWindow().getWidth(),
                                                client.getWindow().getHeight(), Minecraft.ON_OSX);
                        });
                        context.waitTicks(2);

                        int disabledInternal = context.computeOnClient(client -> CbbgGlUtil
                                        .getTextureInternalFormat2D(client.getMainRenderTarget()
                                                        .getColorTextureId()));
                        CbbgGameTestUtil.assertEquals(GL11.GL_RGBA8, disabledInternal,
                                        "MainTarget internal format when cbbg mode is DISABLED");
                } finally {
                        CbbgGameTestUtil.restoreConfig(original);
                }
        }

        private static List<String> getGameInfoLines(DebugScreenOverlay overlay) {
                try {
                        Method m = DebugScreenOverlay.class.getDeclaredMethod("getGameInformation");
                        m.setAccessible(true);
                        Object v = m.invoke(overlay);
                        if (!(v instanceof List<?> raw)) {
                                throw new AssertionError(
                                                "Expected DebugScreenOverlay#getGameInformation to return List, got: "
                                                                + (v == null ? "null"
                                                                                : v.getClass().getName()));
                        }

                        List<String> lines = new ArrayList<>(raw.size());
                        for (Object o : raw) {
                                if (!(o instanceof String s)) {
                                        throw new AssertionError(
                                                        "Expected DebugScreenOverlay#getGameInformation to return List<String>, got element: "
                                                                        + (o == null ? "null"
                                                                                        : o.getClass().getName()));
                                }
                                lines.add(s);
                        }
                        return lines;
                } catch (Exception e) {
                        throw new AssertionError(
                                        "Failed to access DebugScreenOverlay#getGameInformation via reflection",
                                        e);
                }
        }
}
