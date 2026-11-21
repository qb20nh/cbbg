package com.qb20nh.cbbg.config.gui;

import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.ClassicDither;
import com.qb20nh.cbbg.render.ClassicFramebuffers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CbbgConfigScreen extends Screen {
  private final Screen parent;

  public CbbgConfigScreen(Screen parent) {
    super(Component.translatable("cbbg.config.title"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    new CbbgConfigWidgets(
            this::addRenderableWidget,
            font,
            width,
            height,
            ClassicDither.hasFailed(),
            this::confirmGeneration,
            this::onClose)
        .init();
  }

  private void confirmGeneration() {
    CbbgConfig config = CbbgConfig.get();
    minecraft.setScreen(
        new ConfirmScreen(
            confirmed -> {
              if (confirmed) ClassicDither.reload(true);
              minecraft.setScreen(this);
            },
            Component.translatable("cbbg.config.confirm.regenerate_stbn.title"),
            Component.translatable(
                "cbbg.config.confirm.regenerate_stbn.message",
                config.stbnSize(),
                config.stbnDepth(),
                config.stbnSeed())));
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    renderBackground(graphics);
    int cx = width / 2;
    int cy = height / 2;
    int left = cx - CbbgConfigWidgets.CARD_WIDTH / 2;
    int top = cy - CbbgConfigWidgets.CARD_HEIGHT / 2;
    graphics.fill(
        left,
        top,
        left + CbbgConfigWidgets.CARD_WIDTH,
        top + CbbgConfigWidgets.CARD_HEIGHT,
        CbbgConfigWidgets.CARD_BG_COLOR);
    graphics.renderOutline(
        left,
        top,
        CbbgConfigWidgets.CARD_WIDTH,
        CbbgConfigWidgets.CARD_HEIGHT,
        CbbgConfigWidgets.CARD_BORDER_COLOR);
    graphics.drawCenteredString(font, title, cx, top + 10, 0xFFFFFFFF);
    graphics.drawString(
        font,
        Component.translatable("cbbg.config.seed.label").append(":"),
        cx - 100,
        top + 132,
        0xFFAAAAAA,
        false);
    if (IrisCompat.isShaderPackActive()) {
      graphics.drawCenteredString(
          font,
          Component.translatable("cbbg.config.status.iris_active"),
          cx,
          top + CbbgConfigWidgets.CARD_HEIGHT - 36,
          0xFFFFAA00);
    } else if (ClassicDither.hasFailed()) {
      graphics.drawCenteredString(
          font,
          Component.translatable("cbbg.config.status.disabled_render_error"),
          cx,
          top + CbbgConfigWidgets.CARD_HEIGHT - 36,
          0xFFFF5555);
    } else if (ClassicFramebuffers.hasDetectedNoFloatFormats()) {
      graphics.drawCenteredString(
          font,
          Component.translatable("cbbg.config.status.no_float_formats"),
          cx,
          top + CbbgConfigWidgets.CARD_HEIGHT - 36,
          0xFFFFAA00);
    } else if (CbbgConfig.get().mode() == CbbgConfig.Mode.DISABLED) {
      graphics.drawCenteredString(
          font,
          Component.translatable("cbbg.config.status.mode_disabled"),
          cx,
          top + CbbgConfigWidgets.CARD_HEIGHT - 36,
          0xFFAAAAAA);
    }
    super.render(graphics, mouseX, mouseY, partialTick);
  }
}
