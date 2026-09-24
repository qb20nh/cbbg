package com.qb20nh.cbbg.gametest;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.gui.CbbgConfigScreen;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.jspecify.annotations.NonNull;

/**
 * Client-side UI interaction test for {@link CbbgConfigScreen}.
 *
 * <p>
 * This is intentionally a "smoke + interaction" test: - open the screen - perform a handful of
 * interactions (clicks, slider adjustments, text input) - assert config updates - ensure we can
 * close the screen
 */
public class CbbgConfigScreenGameTest implements FabricClientGameTest {

    private static final int WINDOW_WIDTH = 854;
    private static final int WINDOW_HEIGHT = 480;
    private static final int LEFT_MOUSE_BUTTON = 0;

    @Override
    public void runTest(@NonNull ClientGameTestContext context) {
        TestInput input = context.getInput();
        input.resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        context.waitTick();

        CbbgConfig original = CbbgConfig.get();
        setKnownBaselineConfig();

        try {
            context.setScreen(() -> new CbbgConfigScreen(null));
            context.waitForScreen(CbbgConfigScreen.class);
            context.waitTick();
            takeScreenshot(context, "cbbg-config-screen-open");

            // Interact with the screen using widget click handlers directly on the client thread.
            // This avoids coordinate-space ambiguity across platforms/scales while still exercising
            // the UI code paths.
            context.runOnClient(client -> {
                ScreenWidgets w = ScreenWidgets.from(ClientTestAccess.screen(client));
                MouseButtonInfo click = new MouseButtonInfo(LEFT_MOUSE_BUTTON, 0);

                // Pixel format: RGBA16F -> RGBA32F
                w.format.onClick(new MouseButtonEvent(centerX(w.format), centerY(w.format), click),
                        false);

                // Strength slider: click near max
                w.strength.onClick(
                        new MouseButtonEvent(maxClickX(w.strength), centerY(w.strength), click),
                        false);

            });
            // A precision change may reset the renderer; isolate noise edits from that transition.
            context.waitTicks(3);
            var generationBeforeEdits = STBNGenerator.get();
            context.runOnClient(client -> {
                ScreenWidgets w = ScreenWidgets.from(ClientTestAccess.screen(client));
                MouseButtonInfo click = new MouseButtonInfo(LEFT_MOUSE_BUTTON, 0);

                // STBN size/depth: click near min (keeps any background generation small/fast)
                w.stbnSize.onClick(
                        new MouseButtonEvent(minClickX(w.stbnSize), centerY(w.stbnSize), click),
                        false);
                w.stbnDepth.onClick(
                        new MouseButtonEvent(minClickX(w.stbnDepth), centerY(w.stbnDepth), click),
                        false);

                // Seed edit box: set value (triggers responder)
                w.seed.setValue("123");
                w.seed.setValue("12x");
                assertEquals("123", w.seed.getValue(), "invalid seed text");
                w.seed.setCursorPosition(1);
                w.seed.setHighlightPos(2);
                w.seed.insertText("-");
                assertEquals("123", w.seed.getValue(), "invalid seed insertion");
                w.seed.insertText("9");
                assertEquals("193", w.seed.getValue(), "selected seed replacement");
                w.seed.setValue("-42");
                assertEquals(-42L, CbbgConfig.get().stbnSeed(), "negative seed");
                w.seed.moveCursorToEnd(false);
                w.seed.setHighlightPos(0);
                w.seed.insertText("123");
                assertEquals("123", w.seed.getValue(), "reverse seed selection");
                w.seed.setMaxLength(3);
                w.seed.moveCursorToEnd(false);
                w.seed.insertText("4");
                assertEquals("123", w.seed.getValue(), "seed length bound");
                w.seed.setMaxLength(32);

                for (String invalid : List.of("-", "9223372036854775808",
                        "-9223372036854775809")) {
                    w.seed.setValue(invalid);
                    assertEquals(invalid, w.seed.getValue(), "intermediate seed text");
                    var generation = STBNGenerator.get();
                    w.generate.onClick(
                            new MouseButtonEvent(centerX(w.generate), centerY(w.generate), click),
                            false);
                    assertTrue(ClientTestAccess.screen(client) instanceof CbbgConfigScreen,
                            "Invalid seed must not open confirmation");
                    assertEquals(123L, CbbgConfig.get().stbnSeed(), "invalid seed preserves config");
                    assertTrue(STBNGenerator.get() == generation,
                            "Invalid seed must not start generation");
                }
                w.seed.setValue("123");

                // Generate button opens confirmation screen; cancel out.
                w.generate.onClick(
                        new MouseButtonEvent(centerX(w.generate), centerY(w.generate), click),
                        false);
            });
            context.waitForScreen(ConfirmScreen.class);
            takeScreenshot(context, "cbbg-config-screen-confirm");
            context.clickScreenButton("gui.no");
            context.waitForScreen(CbbgConfigScreen.class);

            assertTrue(STBNGenerator.get() == generationBeforeEdits,
                    "Editing or cancelling noise settings must not start generation");
            var previousGeneration = STBNGenerator.get();
            context.clickScreenButton("cbbg.config.button.generate_stbn");
            context.waitForScreen(ConfirmScreen.class);
            context.clickScreenButton("gui.yes");
            context.waitForScreen(CbbgConfigScreen.class);
            assertTrue(STBNGenerator.get() != previousGeneration,
                    "Confirmed generation must start a new job");

            context.runOnClient(client -> {
                ScreenWidgets w = ScreenWidgets.from(ClientTestAccess.screen(client));
                MouseButtonInfo click = new MouseButtonInfo(LEFT_MOUSE_BUTTON, 0);

                // Notifications: true -> false
                assertTrue(w.notifyChat.active && w.notifyToast.active,
                        "Notification controls must be editable before clicking");
                w.notifyChat.onClick(
                        new MouseButtonEvent(centerX(w.notifyChat), centerY(w.notifyChat), click),
                        false);
                w.notifyToast.onClick(
                        new MouseButtonEvent(centerX(w.notifyToast), centerY(w.notifyToast), click),
                        false);

                // Disable last: reopening the screen while disabled locks other settings.
                w.mode.onClick(new MouseButtonEvent(centerX(w.mode), centerY(w.mode), click),
                        false);
            });
            takeScreenshot(context, "cbbg-config-screen-after");

            // Reopening applies the persisted disabled-mode lock, including callback guards.
            context.setScreen(() -> new CbbgConfigScreen(null));
            context.waitForScreen(CbbgConfigScreen.class);
            context.runOnClient(client -> {
                ScreenWidgets w = ScreenWidgets.from(ClientTestAccess.screen(client));
                assertTrue(w.mode.active && w.done.active, "Mode and Done must remain available");
                for (AbstractWidget widget : List.of(w.format, w.strength, w.stbnSize,
                        w.stbnDepth, w.seed, w.generate, w.notifyChat, w.notifyToast)) {
                    assertFalse(widget.active, "disabled control " + widget.getMessage().getString());
                }
                w.seed.setValue("456");
                assertEquals(123L, CbbgConfig.get().stbnSeed(), "locked seed callback");
            });
            takeScreenshot(context, "cbbg-config-screen-locked");

            // Done button closes screen
            context.runOnClient(client -> {
                ScreenWidgets w = ScreenWidgets.from(ClientTestAccess.screen(client));
                MouseButtonInfo click = new MouseButtonInfo(LEFT_MOUSE_BUTTON, 0);
                w.done.onClick(new MouseButtonEvent(centerX(w.done), centerY(w.done), click),
                        false);
            });
            context.waitFor(
                    client -> ClientTestAccess.screen(client) == null || ClientTestAccess.screen(client) instanceof TitleScreen);

            assertConfigUpdated();
        } finally {
            restoreConfig(original);
        }
    }

    private static void setKnownBaselineConfig() {
        CbbgConfig.setMode(CbbgConfig.Mode.ENABLED);
        CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA16F);
        CbbgConfig.setStrength(1.0f);
        CbbgConfig.setStbnSize(32);
        CbbgConfig.setStbnDepth(16);
        CbbgConfig.setStbnSeed(0L);
        CbbgConfig.setNotifyChat(true);
        CbbgConfig.setNotifyToast(true);
    }

    private static void takeScreenshot(ClientGameTestContext context, String name) {
        Path screenshot = context.takeScreenshot(name);
        String evidence = System.getProperty("cbbg.test.evidence");
        if (evidence == null) {
            return;
        }
        try {
            Path directory = Path.of(evidence);
            Files.createDirectories(directory);
            Files.copy(screenshot, directory.resolve(name + "-"
                    + System.getProperty("cbbg.test.backend") + ".png"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException failure) {
            throw new AssertionError("Could not retain UI screenshot", failure);
        }
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

        assertFalse(cfg.notifyChat(), "notifyChat");
        assertFalse(cfg.notifyToast(), "notifyToast");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String name) {
        if (condition) {
            throw new AssertionError("Expected false: " + name);
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
