package com.qb20nh.cbbg.config.gui;

import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.compat.sulkan.ShaderCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class CbbgConfigScreen extends ConfigScreenPlatform {
  private final @Nullable Screen parent;
  private static final int CARD_WIDTH = CbbgConfigWidgets.CARD_WIDTH;
  private static final int CARD_HEIGHT = CbbgConfigWidgets.CARD_HEIGHT;

  public CbbgConfigScreen(@Nullable Screen parent) {
    super(Component.translatable("cbbg.config.title"));
    this.parent = parent;
  }

  public static void open(Screen parent) {
    ClientScreenAccess.setScreen(Minecraft.getInstance(), new CbbgConfigScreen(parent));
  }

  @Override
  public void onClose() {
    if (this.parent != null) {
      ClientScreenAccess.setScreen(this.minecraft, this.parent);
    } else {
      super.onClose();
    }
  }

  private static boolean isIrisActive() {
    return IrisCompat.isShaderPackActive();
  }

  @Override
  protected void init() {
    new CbbgConfigWidgets(
            this::addRenderableWidget,
            this.font,
            this.width,
            this.height,
            DitherController.isDisabled(),
            this::confirmGeneration,
            this::onClose)
        .init();
  }

  private void confirmGeneration() {
    int stbnSize = CbbgConfig.get().stbnSize();
    int stbnDepth = CbbgConfig.get().stbnDepth();
    long stbnSeed = CbbgConfig.get().stbnSeed();

    var confirm =
        confirmation(
            confirmed -> {
              if (confirmed) {
                DitherController.reloadStbn(true); // Force regeneration
              }
              ClientScreenAccess.setScreen(this.minecraft, this);
            },
            Component.translatable("cbbg.config.confirm.regenerate_stbn.title"),
            Component.translatable(
                "cbbg.config.confirm.regenerate_stbn.message", stbnSize, stbnDepth, stbnSeed));
    ClientScreenAccess.setScreen(this.minecraft, confirm);
  }

  @Override
  protected void drawConfig(ConfigCanvas context) {
    // Card background
    int cx = this.width / 2;
    int cy = this.height / 2;
    int x1 = cx - CARD_WIDTH / 2;
    int y1 = cy - CARD_HEIGHT / 2;
    int x2 = cx + CARD_WIDTH / 2;
    int y2 = cy + CARD_HEIGHT / 2;

    context.card(x1, y1, x2, y2);

    // Header
    context.centeredText(this.font, this.title, cx, y1 + 10, 0xFFFFFFFF);

    // Seed Label
    // Layout: yStart + 48 (strength) + 24 (size/depth) + 24 (seed)
    int ySeed = cy - CARD_HEIGHT / 2 + 30 + 48 + 24 + 24 + 6;
    context.text(
        this.font,
        Component.translatable("cbbg.config.seed.label").append(Component.literal(":")),
        cx - 100,
        ySeed,
        0xFFAAAAAA,
        false);

    // Status / warning
    int statusY = y2 - 36;
    final boolean irisActive = isIrisActive();
    if (DitherController.isDisabled()) {
      context.centeredText(
          this.font,
          Component.translatable("cbbg.config.status.disabled_render_error"),
          cx,
          statusY,
          0xFFFF5555);
      statusY += 12;
    }
    if (DitherController.hasDetectedNoFloatFormats()) {
      context.centeredText(
          this.font,
          Component.translatable("cbbg.config.status.no_float_formats"),
          cx,
          statusY,
          0xFFFFAA00);
      statusY += 12;
    }
    if (irisActive) {
      context.centeredText(
          this.font,
          Component.translatable("cbbg.config.status.iris_active"),
          cx,
          statusY,
          0xFFFFAA00);
    } else if (ShaderCompat.isSulkanActive()) {
      context.centeredText(
          this.font,
          Component.translatable("cbbg.config.status.shader_active", "Sulkan"),
          cx,
          statusY,
          0xFFFFAA00);
    } else if (CbbgConfig.get().mode() == CbbgConfig.Mode.DISABLED) {
      context.centeredText(
          this.font,
          Component.translatable("cbbg.config.status.mode_disabled"),
          cx,
          statusY,
          0xFFAAAAAA);
    }
  }
}
