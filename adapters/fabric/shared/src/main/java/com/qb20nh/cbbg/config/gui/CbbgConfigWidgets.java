package com.qb20nh.cbbg.config.gui;

import com.qb20nh.cbbg.platform.Text;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import com.qb20nh.cbbg.config.CbbgConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Shared settings controls; each screen supplies rendering and navigation. */
final class CbbgConfigWidgets {
    static long parseSeed(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        if (!text.matches("-?[0-9]+")) {
            throw new NumberFormatException("Invalid seed");
        }
        return Long.parseLong(text);
    }

    static final int CARD_WIDTH = 260;
    static final int CARD_HEIGHT = 258;
    static final int CARD_BG_COLOR = 0xCC000000;
    static final int CARD_BORDER_COLOR = 0xFF444444;

    private final Consumer<AbstractWidget> addWidget;
    private final Font font;
    private final int width;
    private final int height;
    private final boolean lockedByError;
    private final Runnable confirmGeneration;
    private final Runnable close;

    CbbgConfigWidgets(Consumer<AbstractWidget> addWidget, Font font, int width, int height,
            boolean lockedByError, Runnable confirmGeneration, Runnable close) {
        this.addWidget = addWidget;
        this.font = font;
        this.width = width;
        this.height = height;
        this.lockedByError = lockedByError;
        this.confirmGeneration = confirmGeneration;
        this.close = close;
    }

    private <T extends AbstractWidget> T add(T widget) {
        addWidget.accept(widget);
        return widget;
    }

    private static final Component TOOLTIP_STRENGTH =
            Text.translatable("cbbg.config.tooltip.strength");
    private static final Component TOOLTIP_STBN_SIZE =
            Text.translatable("cbbg.config.tooltip.stbn_size");
    private static final Component TOOLTIP_STBN_DEPTH =
            Text.translatable("cbbg.config.tooltip.stbn_depth");
    private static final Component TOOLTIP_STBN_SEED =
            Text.translatable("cbbg.config.tooltip.stbn_seed");

    private static final Component TOOLTIP_GENERATE_STBN =
            Text.translatable("cbbg.config.tooltip.generate_stbn");

    void init() {
        EditBox seedEdit;
        int cx = this.width / 2;
        int cy = this.height / 2;
        int yStart = cy - CARD_HEIGHT / 2 + 30;

        final boolean lockedByUser = CbbgConfig.get().mode() == CbbgConfig.Mode.DISABLED;

        // 1. Rendering Mode
        CycleButton<CbbgConfig.Mode> modeButton = this
                .add(WidgetPlatform.cycleTooltip(
                        WidgetPlatform.cycle(this::getModeName, CbbgConfig.get().mode(), CbbgConfig.Mode.values()),
                        this::getModeTooltip)
                        .create(cx - 100, yStart, 200, 20,
                                Text.translatable("cbbg.config.mode"), (button, value) -> {
                                    // When cbbg disabled itself due to a render error, keep config
                                    // read-only.
                                    if (lockedByError) {
                                        return;
                                    }
                                    CbbgConfig.setMode(value);
                                }));

        // 1.5 Pixel Format
        CycleButton<CbbgConfig.PixelFormat> formatButton = this.add(WidgetPlatform
                .cycle(CbbgConfigWidgets::getPixelFormatName, CbbgConfig.get().pixelFormat(),
                        CbbgConfig.PixelFormat.RGBA16F, CbbgConfig.PixelFormat.RGBA32F)
                .create(cx - 100, yStart + 24, 200, 20,
                        Text.translatable("cbbg.config.format"), (button, value) -> {
                            if (lockedByError || lockedByUser) {
                                return;
                            }
                            CbbgConfig.setPixelFormat(value);
                        }));

        int y = yStart + 48;

        // 2. Strength Slider (0.5-4.0)
        FloatSlider strengthSlider = new FloatSlider(cx - 100, y, 200, 20,
                Text.translatable("cbbg.config.strength.label"), 0.5f, 4.0f,
                CbbgConfig.get().strength(), v -> {
                    if (lockedByError || lockedByUser) {
                        return;
                    }
                    CbbgConfig.setStrength((float) v);
                });
        WidgetPlatform.tooltip(strengthSlider, TOOLTIP_STRENGTH);
        this.add(strengthSlider);

        y += 24;

        // 3. STBN Size Slider (16-256)
        PowerOfTwoSlider sizeSlider = new PowerOfTwoSlider(cx - 100, y, 98, 20,
                Text.translatable("cbbg.config.size.label"), 16, 256,
                CbbgConfig.get().stbnSize(), v -> {
                    if (lockedByError || lockedByUser) {
                        return;
                    }
                    CbbgConfig.setStbnSize(v);
                });
        WidgetPlatform.tooltip(sizeSlider, TOOLTIP_STBN_SIZE);
        this.add(sizeSlider);

        // 4. STBN Depth Slider (8-128)
        PowerOfTwoSlider depthSlider = new PowerOfTwoSlider(cx + 2, y, 98, 20,
                Text.translatable("cbbg.config.depth.label"), 8, 128,
                CbbgConfig.get().stbnDepth(), v -> {
                    if (lockedByError || lockedByUser) {
                        return;
                    }
                    CbbgConfig.setStbnDepth(v);
                });
        WidgetPlatform.tooltip(depthSlider, TOOLTIP_STBN_DEPTH);
        this.add(depthSlider);

        y += 24;

        // 5. Seed Input
        seedEdit = new SeedEditBox(this.font, cx - 100 + 40, y, 160, 20,
                Text.translatable("cbbg.config.seed.label"));
        seedEdit.setValue(Objects.requireNonNull(Long.toString(CbbgConfig.get().stbnSeed())));
        seedEdit.setResponder(s -> {
            if (lockedByError || lockedByUser) {
                return;
            }
            try {
                long seed = parseSeed(s);
                CbbgConfig.setStbnSeed(seed);
            } catch (NumberFormatException ignored) {
                // Do nothing
            }
        });
        WidgetPlatform.tooltip(seedEdit, TOOLTIP_STBN_SEED);
        this.add(seedEdit);

        y += 24;

        // 6. Generate Button
        Button generateButton = this.add(
                WidgetPlatform.button(Text.translatable("cbbg.config.button.generate_stbn"), b -> {
                    if (lockedByError || lockedByUser) {
                        return;
                    }
                    try {
                        CbbgConfig.setStbnSeed(parseSeed(seedEdit.getValue()));
                    } catch (NumberFormatException ignored) {
                        return;
                    }
                    confirmGeneration.run();
                }, cx - 100, y, 200, 20, TOOLTIP_GENERATE_STBN));

        y += 28;

        // 7. Notifications
        CycleButton<Boolean> chatNotifyButton = this.add(
                CycleButton.onOffBuilder(CbbgConfig.get().notifyChat()).create(cx - 100, y, 98, 20,
                        Text.translatable("cbbg.config.notify.chat"), (b, val) -> {
                            if (lockedByError || lockedByUser) {
                                return;
                            }
                            CbbgConfig.setNotifyChat(val);
                        }));

        CycleButton<Boolean> toastNotifyButton = this.add(
                CycleButton.onOffBuilder(CbbgConfig.get().notifyToast()).create(cx + 2, y, 98, 20,
                        Text.translatable("cbbg.config.notify.toast"), (b, val) -> {
                            if (lockedByError || lockedByUser) {
                                return;
                            }
                            CbbgConfig.setNotifyToast(val);
                        }));

        y += 24;

        // 8. Done Button
        this.add(WidgetPlatform.button(Text.translatable("cbbg.config.button.done"), b -> close.run(),
                cx - 100, y, 200, 20, null));

        // UI lock:
        // - If cbbg disabled itself due to a render error: freeze everything (read-only).
        // - If user Mode is DISABLED: allow changing Mode (to re-enable), but lock everything else.
        // - If Iris is active: still editable (warning only).
        if (lockedByError) {
            modeButton.active = false;
            formatButton.active = false;
            strengthSlider.active = false;
            sizeSlider.active = false;
            depthSlider.active = false;
            seedEdit.active = false;
            seedEdit.setEditable(false);
            generateButton.active = false;
            chatNotifyButton.active = false;
            toastNotifyButton.active = false;
        } else if (lockedByUser) {
            formatButton.active = false;
            strengthSlider.active = false;
            sizeSlider.active = false;
            depthSlider.active = false;
            seedEdit.active = false;
            seedEdit.setEditable(false);
            generateButton.active = false;
            chatNotifyButton.active = false;
            toastNotifyButton.active = false;
        }
    }

    private Component getModeName(CbbgConfig.Mode mode) {
        return switch (mode) {
            case ENABLED -> Text.translatable("cbbg.mode.enabled");
            case DISABLED -> Text.translatable("cbbg.mode.disabled");
            case DEMO -> Text.translatable("cbbg.mode.demo");
        };
    }

    private Component getModeTooltip(CbbgConfig.Mode mode) {
        String tooltipKey = switch (mode) {
            case ENABLED -> "cbbg.config.mode.tooltip.enabled";
            case DISABLED -> "cbbg.config.mode.tooltip.disabled";
            case DEMO -> "cbbg.config.mode.tooltip.demo";
        };
        return Text.translatable(tooltipKey);
    }

    // EditBox removed setFilter in 26.3. Validate edits before mutation on both APIs.
    private static final class SeedEditBox extends EditBox {
        private int selectionEnd;
        private int limit = 32;

        SeedEditBox(Font font, int x, int y, int width, int height, Component label) {
            super(font, x, y, width, height, label);
        }

        @Override
        public void setValue(String value) {
            if (value.matches("-?\\d*")) {
                super.setValue(value);
            }
        }

        @Override
        public void setHighlightPos(int position) {
            super.setHighlightPos(position);
            selectionEnd = Math.max(0, Math.min(position, getValue().length()));
        }

        @Override
        public void setMaxLength(int length) {
            limit = length;
            super.setMaxLength(length);
        }

        @Override
        public void insertText(String input) {
            int start = Math.min(getCursorPosition(), selectionEnd);
            int end = Math.max(getCursorPosition(), selectionEnd);
            int available = limit - getValue().length() + end - start;
            if (available <= 0) {
                return;
            }
            String text = WidgetPlatform.filterText(input);
            text = text.substring(0, Math.min(available, text.length()));
            String proposed = getValue().substring(0, start) + text + getValue().substring(end);
            if (proposed.matches("-?\\d*")) {
                super.insertText(input);
            }
        }
    }

    // Custom Slider for Power-of-Two values
    private static class PowerOfTwoSlider extends AbstractSliderButton {
        private final int min;
        private final int max;
        private final IntConsumer setter;
        private final Component label;

        public PowerOfTwoSlider(int x, int y, int width, int height, Component label, int min,
                int max, int currentValue, IntConsumer setter) {
            super(x, y, width, height, Text.empty(), 0);
            this.label = label;
            this.min = min;
            this.max = max;
            this.setter = setter;

            // Convert current value to 0..1 range logarithmically
            // val = min * 2^steps
            // log2(val/min) = steps
            // range = log2(max/min)
            double range = Math.log(max / (double) min) / Math.log(2);
            double currentSteps = Math.log(currentValue / (double) min) / Math.log(2);
            this.value = currentSteps / range;

            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            int val = getValueInt();
            this.setMessage(Text.translatable("cbbg.config.labeled_value", label,
                    Objects.requireNonNull(Integer.toString(val))));
        }

        @Override
        protected void applyValue() {
            int val = getValueInt();
            setter.accept(val);
        }

        private int getValueInt() {
            double range = Math.log(max / (double) min) / Math.log(2);
            double steps = Math.round(this.value * range);
            return (int) (min * Math.pow(2, steps));
        }
    }

    private static class FloatSlider extends AbstractSliderButton {
        private final float min;
        private final float max;
        private final DoubleConsumer setter;
        private final Component label;

        public FloatSlider(int x, int y, int width, int height, Component label, float min,
                float max, float currentValue, DoubleConsumer setter) {
            super(x, y, width, height, Text.empty(), 0);
            this.label = label;
            this.min = min;
            this.max = max;
            this.setter = setter;

            float clamped = Math.min(max, Math.max(min, currentValue));
            this.value = (clamped - min) / (max - min);
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            float val = getValueFloat();
            this.setMessage(Text.translatable("cbbg.config.labeled_value", label,
                    Objects.requireNonNull(String.format(Locale.ROOT, "%.2f", val))));
        }

        @Override
        protected void applyValue() {
            setter.accept(getValueFloat());
        }

        private float getValueFloat() {
            return min + (float) this.value * (max - min);
        }
    }

    private static Component getPixelFormatName(CbbgConfig.PixelFormat format) {
        return Text.translatable("cbbg.pixel_format." + format.getSerializedName());
    }
}
