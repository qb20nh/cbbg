package com.qb20nh.cbbg.config.gui;

import org.jspecify.annotations.NonNull;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CbbgConfigScreen extends Screen {
    private final Screen parent;
    private static final int CARD_WIDTH = CbbgConfigWidgets.CARD_WIDTH;
    private static final int CARD_HEIGHT = CbbgConfigWidgets.CARD_HEIGHT;
    private static final int CARD_BG_COLOR = CbbgConfigWidgets.CARD_BG_COLOR;
    private static final int CARD_BORDER_COLOR = CbbgConfigWidgets.CARD_BORDER_COLOR;

    private static void renderCard(GuiGraphicsExtractor context, int x1, int y1, int x2, int y2) {
        context.fill(x1, y1, x2, y2, CARD_BG_COLOR);
        context.outline(x1, y1, x2 - x1, y2 - y1, CARD_BORDER_COLOR);
    }

    public CbbgConfigScreen(Screen parent) {
        super(Component.translatable("cbbg.config.title"));
        this.parent = parent;
    }

    @Override
    public void onClose() {
        if (this.parent != null) {
            this.minecraft.gui.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    private static boolean isIrisActive() {
        return IrisCompat.isShaderPackActive();
    }

    @Override
    protected void init() {
        new CbbgConfigWidgets(this::addRenderableWidget, this.font, this.width, this.height,
                DitherController.isDisabled(), this::confirmGeneration, this::onClose).init();
    }

    private void confirmGeneration() {
        int stbnSize = CbbgConfig.get().stbnSize();
        int stbnDepth = CbbgConfig.get().stbnDepth();
        long stbnSeed = CbbgConfig.get().stbnSeed();

        ConfirmScreen confirm = new ConfirmScreen(confirmed -> {
            if (confirmed) {
                DitherController.reloadStbn(true); // Force regeneration
            }
            this.minecraft.gui.setScreen(this);
        }, Component.translatable("cbbg.config.confirm.regenerate_stbn.title"),
                Component.translatable("cbbg.config.confirm.regenerate_stbn.message",
                        stbnSize, stbnDepth, stbnSeed)) {
            @Override
            public void extractBackground(@NonNull GuiGraphicsExtractor context, int mouseX,
                    int mouseY, float partialTick) {
                CbbgConfigScreen.this.renderSafeBackground(context, partialTick);
            }

            @Override
            public void extractRenderState(@NonNull GuiGraphicsExtractor context, int mouseX, int mouseY,
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

                super.extractRenderState(context, mouseX, mouseY, partialTick);
            }
        };
        this.minecraft.gui.setScreen(confirm);
    }

    @Override
    public void extractBackground(@NonNull GuiGraphicsExtractor context, int mouseX, int mouseY,
            float partialTick) {
        this.renderSafeBackground(context, partialTick);
    }

    private void renderSafeBackground(@NonNull GuiGraphicsExtractor context, float partialTick) {
        if (this.minecraft.level != null) {
            this.extractTransparentBackground(context);
        } else {
            this.extractPanorama(context, partialTick);
            this.extractMenuBackground(context);
        }
    }

    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTick) {
        // Card background
        int cx = this.width / 2;
        int cy = this.height / 2;
        int x1 = cx - CARD_WIDTH / 2;
        int y1 = cy - CARD_HEIGHT / 2;
        int x2 = cx + CARD_WIDTH / 2;
        int y2 = cy + CARD_HEIGHT / 2;

        renderCard(context, x1, y1, x2, y2);

        // Header
        context.centeredText(this.font, this.title, cx, y1 + 10, 0xFFFFFFFF);

        // Seed Label
        // Layout: yStart + 48 (strength) + 24 (size/depth) + 24 (seed)
        int ySeed = cy - CARD_HEIGHT / 2 + 30 + 48 + 24 + 24 + 6;
        context.text(this.font,
                Component.translatable("cbbg.config.seed.label").append(Component.literal(":")),
                cx - 100, ySeed, 0xFFAAAAAA, false);

        // Status / warning
        int statusY = y2 - 36;
        final boolean irisActive = isIrisActive();
        if (DitherController.isDisabled()) {
            context.centeredText(this.font,
                    Component.translatable("cbbg.config.status.disabled_render_error"), cx, statusY,
                    0xFFFF5555);
            statusY += 12;
        }
        if (DitherController.hasDetectedNoFloatFormats()) {
            context.centeredText(this.font,
                    Component.translatable("cbbg.config.status.no_float_formats"), cx, statusY,
                    0xFFFFAA00);
            statusY += 12;
        }
        if (irisActive) {
            context.centeredText(this.font,
                    Component.translatable("cbbg.config.status.iris_active"), cx, statusY,
                    0xFFFFAA00);
        } else if (CbbgConfig.get().mode() == CbbgConfig.Mode.DISABLED) {
            context.centeredText(this.font,
                    Component.translatable("cbbg.config.status.mode_disabled"), cx, statusY,
                    0xFFAAAAAA);
        }

        super.extractRenderState(context, mouseX, mouseY, partialTick);
    }

}
