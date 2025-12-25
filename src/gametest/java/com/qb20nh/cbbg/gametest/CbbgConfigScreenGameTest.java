package com.qb20nh.cbbg.gametest;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.gui.CbbgConfigScreen;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Client-side UI interaction test for {@link CbbgConfigScreen}.
 *
 * <p>
 * This is intentionally a "smoke + interaction" test: - open the screen - perform a handful of
 * interactions (clicks, slider adjustments, text input) - assert config updates - ensure we can
 * close the screen
 */
public class CbbgConfigScreenGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        CbbgConfig original = CbbgConfig.get();
        setKnownBaselineConfig();

        try {
            context.setScreen(() -> new CbbgConfigScreen(null));
            context.waitForScreen(CbbgConfigScreen.class);
            context.waitTick();
            context.takeScreenshot("cbbg-config-screen-open");

            // Interact with the screen using widget click handlers directly on the client thread.
            // This avoids coordinate-space ambiguity across platforms/scales while still exercising
            // the UI code paths.
            context.runOnClient(client -> {
                ScreenWidgets w = ScreenWidgets.from(client.screen);

                // Mode: ENABLED -> DISABLED
                w.mode.mouseClicked(centerX(w.mode), centerY(w.mode), 0);

                // Pixel format: RGBA16F -> RGBA32F
                w.format.mouseClicked(centerX(w.format), centerY(w.format), 0);

                // Strength slider: click near max
                w.strength.mouseClicked(maxClickX(w.strength), centerY(w.strength), 0);

                // STBN size/depth: click near min (keeps any background generation small/fast)
                w.stbnSize.mouseClicked(minClickX(w.stbnSize), centerY(w.stbnSize), 0);
                w.stbnDepth.mouseClicked(minClickX(w.stbnDepth), centerY(w.stbnDepth), 0);

                // Seed edit box: set value (triggers responder)
                w.seed.setValue("123");

                // Generate button opens confirmation screen; cancel out.
                w.generate.mouseClicked(centerX(w.generate), centerY(w.generate), 0);
            });
            context.waitForScreen(ConfirmScreen.class);
            context.takeScreenshot("cbbg-config-screen-confirm");
            context.clickScreenButton("gui.no");
            context.waitForScreen(CbbgConfigScreen.class);

            context.runOnClient(client -> {
                ScreenWidgets w = ScreenWidgets.from(client.screen);

                // Notifications: true -> false
                w.notifyChat.mouseClicked(centerX(w.notifyChat), centerY(w.notifyChat), 0);
                w.notifyToast.mouseClicked(centerX(w.notifyToast), centerY(w.notifyToast), 0);
            });
            context.takeScreenshot("cbbg-config-screen-after");

            // Done button closes screen
            context.runOnClient(client -> {
                ScreenWidgets w = ScreenWidgets.from(client.screen);
                w.done.mouseClicked(centerX(w.done), centerY(w.done), 0);
            });
            context.waitFor(
                    client -> client.screen == null || client.screen instanceof TitleScreen);

            assertConfigUpdated();
        } finally {
            restoreConfig(original);
        }
    }

    private static void setKnownBaselineConfig() {
        CbbgConfig.setMode(CbbgConfig.Mode.ENABLED);
        CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA16F);
        CbbgConfig.setStrength(1.0f);
        CbbgConfig.setStbnSize(128);
        CbbgConfig.setStbnDepth(64);
        CbbgConfig.setStbnSeed(0L);
        CbbgConfig.setNotifyChat(true);
        CbbgConfig.setNotifyToast(true);
    }

    private static void restoreConfig(CbbgConfig cfg) {
        if (cfg == null) {
            return;
        }
        CbbgConfig.setMode(cfg.mode());
        CbbgConfig.setPixelFormat(cfg.pixelFormat());
        CbbgConfig.setStrength(cfg.strength());
        CbbgConfig.setStbnSize(cfg.stbnSize());
        CbbgConfig.setStbnDepth(cfg.stbnDepth());
        CbbgConfig.setStbnSeed(cfg.stbnSeed());
        CbbgConfig.setNotifyChat(cfg.notifyChat());
        CbbgConfig.setNotifyToast(cfg.notifyToast());
    }

    private static void assertConfigUpdated() {
        CbbgConfig cfg = CbbgConfig.get();

        assertEquals(CbbgConfig.Mode.DISABLED, cfg.mode(), "mode");
        assertEquals(CbbgConfig.PixelFormat.RGBA32F, cfg.pixelFormat(), "pixelFormat");

        // Strength slider can land on slightly below max depending on click rounding.
        assertTrue(cfg.strength() >= 3.5f, "strength expected >= 3.5, got " + cfg.strength());

        assertEquals(16, cfg.stbnSize(), "stbnSize");
        assertEquals(8, cfg.stbnDepth(), "stbnDepth");
        assertEquals(123L, cfg.stbnSeed(), "stbnSeed");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String name) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                    "Mismatch for " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertEquals(int expected, int actual, String name) {
        if (expected != actual) {
            throw new AssertionError(
                    "Mismatch for " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertEquals(long expected, long actual, String name) {
        if (expected != actual) {
            throw new AssertionError(
                    "Mismatch for " + name + ": expected=" + expected + " actual=" + actual);
        }
    }


    private record ScreenWidgets(CycleButton<?> mode, CycleButton<?> format,
            AbstractSliderButton strength, AbstractSliderButton stbnSize,
            AbstractSliderButton stbnDepth, EditBox seed, Button generate,
            CycleButton<?> notifyChat, CycleButton<?> notifyToast, Button done) {

        static ScreenWidgets from(Screen screen) {
            if (!(screen instanceof CbbgConfigScreen)) {
                throw new IllegalStateException("Expected CbbgConfigScreen, got: " + screen);
            }

            List<Object> widgets = new ArrayList<>();
            for (GuiEventListener child : screen.children()) {
                widgets.add(child);
            }

            if (widgets.size() < 10) {
                throw new IllegalStateException(
                        "Expected >= 10 widgets, got " + widgets.size() + ": " + widgets);
            }

            return new ScreenWidgets(cast(widgets.get(0), CycleButton.class, "mode"),
                    cast(widgets.get(1), CycleButton.class, "format"),
                    cast(widgets.get(2), AbstractSliderButton.class, "strength"),
                    cast(widgets.get(3), AbstractSliderButton.class, "stbnSize"),
                    cast(widgets.get(4), AbstractSliderButton.class, "stbnDepth"),
                    cast(widgets.get(5), EditBox.class, "seed"),
                    cast(widgets.get(6), Button.class, "generate"),
                    cast(widgets.get(7), CycleButton.class, "notifyChat"),
                    cast(widgets.get(8), CycleButton.class, "notifyToast"),
                    cast(widgets.get(9), Button.class, "done"));
        }
    }

    private static <T> T cast(Object obj, Class<T> type, String name) {
        if (!type.isInstance(obj)) {
            throw new IllegalStateException(
                    "Expected " + name + " widget of type " + type.getName() + ", got: " + obj);
        }
        return type.cast(obj);
    }

    private static int centerX(AbstractWidget w) {
        return w.getX() + w.getWidth() / 2;
    }

    private static int centerY(AbstractWidget w) {
        return w.getY() + w.getHeight() / 2;
    }

    private static int maxClickX(AbstractWidget w) {
        // Click near the right edge to set slider to (almost) max.
        return w.getX() + w.getWidth() - 1;
    }

    private static int minClickX(AbstractWidget w) {
        // Click near the left edge to set slider to (almost) min.
        return w.getX() + 1;
    }
}
