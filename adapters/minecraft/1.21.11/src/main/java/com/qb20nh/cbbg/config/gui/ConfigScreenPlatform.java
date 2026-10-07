package com.qb20nh.cbbg.config.gui;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
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
  }

  private void safeBackground(GuiGraphics graphics, float delta) {
    if (minecraft.level != null) {
      renderTransparentBackground(graphics);
    } else {
      renderPanorama(graphics, delta);
      renderMenuBackground(graphics);
    }
  }

  @Override
  public void render(GuiGraphics graphics, int x, int y, float delta) {
    drawConfig(new GuiGraphicsCanvas(graphics));
    super.render(graphics, x, y, delta);
  }

  protected ConfirmScreen confirmation(BooleanConsumer action, Component title, Component message) {
    return new ConfirmScreen(action, title, message) {
      @Override
      public void renderBackground(GuiGraphics graphics, int x, int y, float delta) {
        ConfigScreenPlatform.this.safeBackground(graphics, delta);
      }

      @Override
      public void render(GuiGraphics graphics, int x, int y, float delta) {
        new GuiGraphicsCanvas(graphics)
            .card(
                Math.max(0, layout.getX() - 12),
                Math.max(0, layout.getY() - 12),
                Math.min(width, layout.getX() + layout.getWidth() + 12),
                Math.min(height, layout.getY() + layout.getHeight() + 12));
        super.render(graphics, x, y, delta);
      }
    };
  }
}
