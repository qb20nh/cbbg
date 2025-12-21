package com.qb20nh.cbbg.config.gui;

import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import org.jspecify.annotations.NonNull;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.CbbgDither;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CbbgConfigScreen extends Screen {
    private final Screen parent;
    private static final int CARD_WIDTH = 260; // Slightly wider for sliders
    private static final int CARD_HEIGHT = 258; // Extra room for status lines / locking notice
    private static final int CARD_BG_COLOR = 0xCC000000; // 80% opacity black
    private static final int CARD_BORDER_COLOR = 0xFF444444; // Dark grey border

    private static final Tooltip TOOLTIP_STRENGTH = Tooltip.create(Component.literal(
            "Controls dithering intensity.\n\n1.00 is the default. Higher values increase dithering strength.\nRange: 0.50–4.00"));
    private static final Tooltip TOOLTIP_STBN_SIZE = Tooltip.create(Component.literal(
            "Noise texture size (width/height).\nMust be a power of two.\n\nLarger values reduce visible repetition but use more memory and may take longer to generate.\nRange: 16–256"));
    private static final Tooltip TOOLTIP_STBN_DEPTH = Tooltip.create(Component.literal(
            "Noise texture depth (number of frames).\nMust be a power of two.\n\nHigher values reduce temporal repetition but use more memory and may take longer to generate.\nRange: 8–128"));
    private static final Tooltip TOOLTIP_STBN_SEED = Tooltip.create(Component.literal(
            "Seed used for noise generation.\n\nChanging this will produce a different dithering pattern.\nOnly integers are accepted."));

    private static final Tooltip TOOLTIP_GENERATE_STBN = Tooltip.create(Component
            .literal("Regenerates noise textures (after confirmation). May take a moment."));

    private static void renderCard(GuiGraphics context, int x1, int y1, int x2, int y2) {
        context.fill(x1, y1, x2, y2, CARD_BG_COLOR);
        context.renderOutline(x1, y1, x2 - x1, y2 - y1, CARD_BORDER_COLOR);
    }

    public CbbgConfigScreen(Screen parent) {
        super(Component.literal("cbbg config"));
        this.parent = parent;
    }

    private static boolean isIrisActive() {
        return IrisCompat.isShaderPackActive();
    }

    @Override
    protected void init() {
        EditBox seedEdit;
        int cx = this.width / 2;
        int cy = this.height / 2;
        int yStart = cy - CARD_HEIGHT / 2 + 30;

        final boolean lockedByError = CbbgDither.isDisabled();
        final boolean lockedByUser = CbbgConfig.get().mode() == CbbgConfig.Mode.DISABLED;

        // 1. Rendering Mode
        CycleButton<CbbgConfig.Mode> modeButton =
                this.addRenderableWidget(CycleButton
                        .builder(this::getModeName, CbbgConfig.get().mode())
                .withTooltip(this::getModeTooltip).withValues(CbbgConfig.Mode.values())
                .create(cx - 100, yStart, 200, 20, Component.literal("Mode"),
                        (button, value) -> {
                            // When cbbg disabled itself due to a render error, keep config read-only.
                            if (lockedByError) {
                                return;
                            }
                            CbbgConfig.setMode(value);
                        }));

        // 1.5 Pixel Format
        CycleButton<CbbgConfig.PixelFormat> formatButton = this.addRenderableWidget(
                CycleButton
                        .builder(
                                (CbbgConfig.PixelFormat f) -> Component
                                        .literal(f.getSerializedName()),
                                CbbgConfig.get().pixelFormat())
                        .withValues(CbbgConfig.PixelFormat.RGBA16F, CbbgConfig.PixelFormat.RGBA32F)
                        .create(cx - 100, yStart + 24, 200, 20, Component.literal("Format"),
                                (button, value) -> {
                                    if (lockedByError || lockedByUser) {
                                        return;
                                    }
                                    CbbgConfig.setPixelFormat(value);
                                }));

        int y = yStart + 48;

        // 2. Strength Slider (0.5-4.0)
        FloatSlider strengthSlider =
                new FloatSlider(cx - 100, y, 200, 20, Component.literal("Strength: "), 0.5f, 4.0f,
                        CbbgConfig.get().strength(), v -> {
                            if (lockedByError || lockedByUser) {
                                return;
                            }
                            CbbgConfig.setStrength((float) v);
                        });
        strengthSlider.setTooltip(TOOLTIP_STRENGTH);
        this.addRenderableWidget(strengthSlider);

        y += 24;

        // 3. STBN Size Slider (16-256)
        PowerOfTwoSlider sizeSlider =
                new PowerOfTwoSlider(cx - 100, y, 98, 20, Component.literal("Size: "), 16, 256,
                        CbbgConfig.get().stbnSize(), v -> {
                            if (lockedByError || lockedByUser) {
                                return;
                            }
                            CbbgConfig.setStbnSize(v);
                        });
        sizeSlider.setTooltip(TOOLTIP_STBN_SIZE);
        this.addRenderableWidget(sizeSlider);

        // 4. STBN Depth Slider (8-128)
        PowerOfTwoSlider depthSlider =
                new PowerOfTwoSlider(cx + 2, y, 98, 20, Component.literal("Depth: "), 8, 128,
                        CbbgConfig.get().stbnDepth(), v -> {
                            if (lockedByError || lockedByUser) {
                                return;
                            }
                            CbbgConfig.setStbnDepth(v);
                        });
        depthSlider.setTooltip(TOOLTIP_STBN_DEPTH);
        this.addRenderableWidget(depthSlider);

        y += 24;

        // 5. Seed Input
        seedEdit = new EditBox(this.font, cx - 100 + 40, y, 160, 20, Component.literal("Seed"));
        seedEdit.setValue(Objects.requireNonNull(Long.toString(CbbgConfig.get().stbnSeed())));
        seedEdit.setFilter(s -> s.matches("-?\\d*")); // Only integers
        seedEdit.setResponder(s -> {
            if (lockedByError || lockedByUser) {
                return;
            }
            try {
                long seed = (s == null || s.isEmpty()) ? 0 : Long.parseLong(s);
                CbbgConfig.setStbnSeed(seed);
            } catch (NumberFormatException ignored) {
                // Do nothing
            }
        });
        seedEdit.setTooltip(TOOLTIP_STBN_SEED);
        this.addRenderableWidget(seedEdit);

        y += 24;

        // 6. Generate Button
        Button generateButton =
                this.addRenderableWidget(Button.builder(Component.literal("Generate STBN Textures"),
                        b -> {
                            if (lockedByError || lockedByUser) {
                                return;
                            }
            int stbnSize = CbbgConfig.get().stbnSize();
            int stbnDepth = CbbgConfig.get().stbnDepth();
            long stbnSeed = CbbgConfig.get().stbnSeed();

            ConfirmScreen confirm = new ConfirmScreen(confirmed -> {
                if (confirmed) {
                    CbbgDither.reloadStbn(true); // Force regeneration
                }
                this.minecraft.setScreen(this);
            }, Component.literal("Regenerate STBN textures?"),
                    Component.literal("This will regenerate the STBN noise textures with:\n\n"
                            + "Size: " + stbnSize + "\nDepth: " + stbnDepth + "\nSeed: " + stbnSeed
                            + "\n\nThis may take a moment.")) {
                @Override
                public void renderBackground(@NonNull GuiGraphics context, int mouseX, int mouseY,
                        float partialTick) {
                    CbbgConfigScreen.this.renderSafeBackground(context, partialTick);
                }

                @Override
                public void render(@NonNull GuiGraphics context, int mouseX, int mouseY,
                        float partialTick) {
                    // Draw the same card styling behind the confirm dialog UI.
                    final int padX = 12;
                    final int padY = 12;
                    int x1 = Math.max(0, this.layout.getX() - padX);
                    int y1 = Math.max(0, this.layout.getY() - padY);
                    int x2 = Math.min(this.width,
                            this.layout.getX() + this.layout.getWidth() + padX);
                    int y2 = Math.min(this.height,
                            this.layout.getY() + this.layout.getHeight() + padY);
                    renderCard(context, x1, y1, x2, y2);

                    super.render(context, mouseX, mouseY, partialTick);
                }
            };
            this.minecraft.setScreen(confirm);
                        }).bounds(cx - 100, y, 200, 20).tooltip(TOOLTIP_GENERATE_STBN).build());

        y += 28;

        // 7. Notifications
        CycleButton<Boolean> chatNotifyButton =
                this.addRenderableWidget(CycleButton.onOffBuilder(CbbgConfig.get().notifyChat())
                        .create(cx - 100, y, 98, 20, Component.literal("Chat Notify"), (b, val) -> {
                            if (lockedByError || lockedByUser) {
                                return;
                            }
                            CbbgConfig.setNotifyChat(val);
                        }));

        CycleButton<Boolean> toastNotifyButton =
                this.addRenderableWidget(CycleButton.onOffBuilder(CbbgConfig.get().notifyToast())
                        .create(cx + 2, y, 98, 20, Component.literal("Toast Notify"), (b, val) -> {
                            if (lockedByError || lockedByUser) {
                                return;
                            }
                            CbbgConfig.setNotifyToast(val);
                        }));

        y += 24;

        // 8. Done Button
        this.addRenderableWidget(Button
                .builder(Component.literal("Done"), b -> this.minecraft.setScreen(this.parent))
                .bounds(cx - 100, y, 200, 20).build());

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
            case ENABLED -> Component.literal("Enabled");
            case DISABLED -> Component.literal("Disabled");
            case DEMO -> Component.literal("Demo (Split)");
        };
    }

    private Tooltip getModeTooltip(CbbgConfig.Mode mode) {
        String key = switch (mode) {
            case ENABLED -> "Full color bit-depth reduction with STBN dithering.";
            case DISABLED -> "Vanilla rendering (no changes).";
            case DEMO -> "Split-screen: Left = Vanilla, Right = Dithered.";
        };
        return Tooltip.create(Component.literal(key));
    }

    @Override
    public void renderBackground(@NonNull GuiGraphics context, int mouseX, int mouseY,
            float partialTick) {
        this.renderSafeBackground(context, partialTick);
    }

    private void renderSafeBackground(@NonNull GuiGraphics context, float partialTick) {
        if (this.minecraft.level != null) {
            this.renderTransparentBackground(context);
        } else {
            this.renderPanorama(context, partialTick);
            this.renderMenuBackground(context);
        }
    }

    @Override
    public void render(@NonNull GuiGraphics context, int mouseX, int mouseY, float partialTick) {
        // Card background
        int cx = this.width / 2;
        int cy = this.height / 2;
        int x1 = cx - CARD_WIDTH / 2;
        int y1 = cy - CARD_HEIGHT / 2;
        int x2 = cx + CARD_WIDTH / 2;
        int y2 = cy + CARD_HEIGHT / 2;

        renderCard(context, x1, y1, x2, y2);

        // Header
        context.drawCenteredString(this.font, this.title, cx, y1 + 10, 0xFFFFFFFF);

        // Seed Label
        // Layout: yStart + 48 (strength) + 24 (size/depth) + 24 (seed)
        int ySeed = cy - CARD_HEIGHT / 2 + 30 + 48 + 24 + 24 + 6;
        context.drawString(this.font, "Seed:", cx - 100, ySeed, 0xFFAAAAAA, false);

        // Status / warning
        int statusY = y2 - 36;
        final boolean irisActive = isIrisActive();
        if (CbbgDither.isDisabled()) {
            context.drawCenteredString(this.font,
                    Component.literal("⚠ cbbg disabled due to render error (see log)"), cx, statusY,
                    0xFFFF5555);
            statusY += 12;
        }
        if (MainTargetFormatSupport.hasDetectedNoFloatFormats()) {
            context.drawCenteredString(this.font,
                    Component.literal("⚠ No RGBA16F/32F support: stuck on RGBA8"), cx, statusY,
                    0xFFFFAA00);
            statusY += 12;
        }
        if (irisActive) {
            context.drawCenteredString(this.font,
                    Component.literal("⚠ Iris active: cbbg forced OFF"), cx, statusY, 0xFFFFAA00);
        } else if (CbbgConfig.get().mode() == CbbgConfig.Mode.DISABLED) {
            context.drawCenteredString(this.font,
                    Component.literal("cbbg is disabled (enable Mode to edit settings)"), cx,
                    statusY, 0xFFAAAAAA);
        }

        super.render(context, mouseX, mouseY, partialTick);
    }

    // Custom Slider for Power-of-Two values
    private static class PowerOfTwoSlider extends AbstractSliderButton {
        private final int min;
        private final int max;
        private final IntConsumer setter;
        private final Component prefix;

        public PowerOfTwoSlider(int x, int y, int width, int height, Component prefix, int min,
                int max, int currentValue, IntConsumer setter) {
            super(x, y, width, height, Component.empty(), 0);
            this.prefix = prefix;
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
            this.setMessage(prefix.copy().append(Objects.requireNonNull(Integer.toString(val))));
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
        private final Component prefix;

        public FloatSlider(int x, int y, int width, int height, Component prefix, float min,
                float max, float currentValue, DoubleConsumer setter) {
            super(x, y, width, height, Component.empty(), 0);
            this.prefix = prefix;
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
            this.setMessage(prefix.copy().append(Component
                    .literal(Objects.requireNonNull(String.format(Locale.ROOT, "%.2f", val)))));
        }

        @Override
        protected void applyValue() {
            setter.accept(getValueFloat());
        }

        private float getValueFloat() {
            return min + (float) this.value * (max - min);
        }
    }
}
