package com.qb20nh.cbbg.config.gui;

import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

public final class CbbgConfigScreen extends Screen {
    private final Screen parent;
    private static final int CARD_WIDTH = 240;
    private static final int CARD_HEIGHT = 160;

    public CbbgConfigScreen(Screen parent) {
        super(Component.literal("cbbg config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        Component modeLabel = Component.literal("Rendering Mode");

        this.addRenderableWidget(CycleButton.<CbbgConfig.Mode>builder(this::getModeName,
                () -> CbbgConfig.get().mode()).withValues(CbbgConfig.Mode.values())
                .withTooltip(this::getModeTooltip).create(cx - 100, cy - 20, 200, 20, modeLabel,
                        (button, value) -> CbbgConfig.setMode(value)));

        this.addRenderableWidget(Button
                .builder(Component.literal("Done"), b -> this.minecraft.setScreen(this.parent))
                .bounds(cx - 100, cy + 50, 200, 20).build());
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
            case DEMO -> "Split-screen: Left = Dithered, Right = Vanilla.";
        };
        return Tooltip.create(Component.literal(key));
    }

    @Override
    public void render(@NonNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Manually render background to avoid double-blur crash issues with some mods
        graphics.fill(0, 0, this.width, this.height, 0xA0000000);
        // Draw centered dark "card" background
        int cx = this.width / 2;
        int cy = this.height / 2;
        int x1 = cx - CARD_WIDTH / 2;
        int y1 = cy - CARD_HEIGHT / 2;
        int x2 = cx + CARD_WIDTH / 2;
        int y2 = cy + CARD_HEIGHT / 2;

        graphics.fill(x1, y1, x2, y2, 0xCC000000); // 80% opacity black
        graphics.renderOutline(x1, y1, CARD_WIDTH, CARD_HEIGHT, 0xFF444444); // Dark grey border

        // Header
        graphics.drawCenteredString(this.font, this.title, cx, y1 + 15, 0xFFFFFFFF);

        // Status / Info
        boolean irisActive = IrisCompat.isShaderPackActive();
        if (irisActive) {
            graphics.drawCenteredString(this.font,
                    Component.literal("⚠ Iris shaderpack active: cbbg forced OFF"), cx, cy + 15,
                    0xFFFF5555);
        } else {
            CbbgConfig.Mode effective = CbbgClient.getEffectiveMode();
            graphics.drawCenteredString(this.font,
                    Component.literal("Active: " + getModeName(effective).getString()), cx, cy + 15,
                    0xFFAAAAAA);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
