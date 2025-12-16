package com.qb20nh.cbbg.config.gui;

import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

public final class CbbgConfigScreen extends Screen {
  private final Screen parent;

  private Button enabledButton;
  private Button disabledButton;
  private Button demoButton;

  public CbbgConfigScreen(Screen parent) {
    super(Component.literal("cbbg"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    int cx = this.width / 2;
    int y = 60;
    int w = 120;
    int h = 20;
    int gap = 8;

    this.enabledButton = this.addRenderableWidget(
        Button.builder(Component.literal("Enabled"), b -> setMode(CbbgConfig.Mode.ENABLED))
            .bounds(cx - w - gap / 2, y, w, h)
            .build());

    this.disabledButton = this.addRenderableWidget(
        Button.builder(Component.literal("Disabled"), b -> setMode(CbbgConfig.Mode.DISABLED))
            .bounds(cx + gap / 2, y, w, h)
            .build());

    this.demoButton = this.addRenderableWidget(
        Button.builder(
            Component.literal("Demo (split)"), b -> setMode(CbbgConfig.Mode.DEMO))
            .bounds(cx - w / 2, y + h + gap, w, h)
            .build());

    this.addRenderableWidget(
        Button.builder(Component.literal("Done"), b -> this.minecraft.setScreen(this.parent))
            .bounds(cx - 100, this.height - 28, 200, h)
            .build());

    refreshButtons();
  }

  private static void setMode(CbbgConfig.Mode mode) {
    CbbgConfig.setMode(mode);
  }

  private void refreshButtons() {
    CbbgConfig.Mode mode = CbbgConfig.get().mode();
    if (this.enabledButton != null)
      this.enabledButton.active = mode != CbbgConfig.Mode.ENABLED;
    if (this.disabledButton != null)
      this.disabledButton.active = mode != CbbgConfig.Mode.DISABLED;
    if (this.demoButton != null)
      this.demoButton.active = mode != CbbgConfig.Mode.DEMO;
  }

  @Override
  public void tick() {
    super.tick();
    refreshButtons();
  }

  @Override
  public void render(@NonNull GuiGraphics graphics, int mouseX, int mouseY,
      float partialTick) {
    // Avoid Screen#renderBackground here: it applies a blur, and some screen
    // transitions can already
    // blur earlier in the same frame (Fabric Screen API / Mod Menu), which would
    // crash with
    // "Can only blur once per frame".
    graphics.fill(0, 0, this.width, this.height, 0xA0000000);
    graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);

    CbbgConfig.Mode user = CbbgClient.getUserMode();
    CbbgConfig.Mode effective = CbbgClient.getEffectiveMode();
    boolean irisActive = IrisCompat.isShaderPackActive();

    graphics.drawCenteredString(
        this.font,
        Component.literal("Mode: " + user + "   (effective: " + effective + ")"),
        this.width / 2,
        35,
        0xFFA0A0A0);

    if (irisActive) {
      graphics.drawCenteredString(
          this.font,
          Component.literal("Iris shaderpack active: cbbg forced OFF"),
          this.width / 2,
          46,
          0xFFFF5555);
    } else {
      graphics.drawCenteredString(
          this.font,
          Component.literal("Demo = left enabled (dither), right disabled (no dither)"),
          this.width / 2,
          46,
          0xFFA0A0A0);
    }

    super.render(graphics, mouseX, mouseY, partialTick);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
