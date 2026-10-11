package com.qb20nh.cbbg.config.gui;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;

@NullMarked
public abstract class ConfigScreenPlatform extends Screen {
  protected ConfigScreenPlatform(Component title) {
    super(title);
  }

  protected abstract void drawConfig(ConfigCanvas canvas);

  @Override
  public void renderBackground(GuiGraphics graphics, int x, int y, float delta) {
    safeBackground(graphics, delta);
    drawConfig(new GuiGraphicsCanvas(graphics));
  }

  private void safeBackground(GuiGraphics graphics, float delta) {
    if (Minecraft.getInstance().level != null) {
      renderTransparentBackground(graphics);
    } else {
      renderPanorama(graphics, delta);
      renderMenuBackground(graphics);
    }
  }

  protected ConfirmScreen confirmation(BooleanConsumer action, Component title, Component message) {
    return new ConfirmScreen(action, title, message) {
      @Override
      public void renderBackground(GuiGraphics graphics, int x, int y, float delta) {
        ConfigScreenPlatform.this.safeBackground(graphics, delta);
        new GuiGraphicsCanvas(graphics)
            .card(
                Math.max(0, width / 2 - 172),
                40,
                Math.min(width, width / 2 + 172),
                Math.max(40, height - 24));
      }
    };
  }
}
