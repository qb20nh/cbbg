package com.qb20nh.cbbg.config.gui;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
  public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) {
    safeBackground(graphics, delta);
  }

  private void safeBackground(GuiGraphicsExtractor graphics, float delta) {
    if (minecraft.level != null) {
      extractTransparentBackground(graphics);
    } else {
      extractPanorama(graphics, delta);
      extractMenuBackground(graphics);
    }
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
    drawConfig(new GuiExtractorConfigCanvas(graphics));
    super.extractRenderState(graphics, x, y, delta);
  }

  protected ConfirmScreen confirmation(BooleanConsumer action, Component title, Component message) {
    return new ConfirmScreen(action, title, message) {
      @Override
      public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        ConfigScreenPlatform.this.safeBackground(graphics, delta);
      }

      @Override
      public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        new GuiExtractorConfigCanvas(graphics)
            .card(
                Math.max(0, layout.getX() - 12),
                Math.max(0, layout.getY() - 12),
                Math.min(width, layout.getX() + layout.getWidth() + 12),
                Math.min(height, layout.getY() + layout.getHeight() + 12));
        super.extractRenderState(graphics, x, y, delta);
      }
    };
  }
}
