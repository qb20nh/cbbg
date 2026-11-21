package com.qb20nh.cbbg.config.gui;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.Font;
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
  public void extractBackground(GuiGraphicsExtractor graphics) {
    safeBackground(graphics);
  }

  private void safeBackground(GuiGraphicsExtractor graphics) {
    if (minecraft.level != null) {
      extractTransparentBackground(graphics);
    } else {
      extractPanorama(graphics);
      extractMenuBackground(graphics);
    }
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics) {
    drawConfig(new Canvas(graphics));
    super.extractRenderState(graphics);
  }

  protected ConfirmScreen confirmation(BooleanConsumer action, Component title, Component message) {
    return new ConfirmScreen(action, title, message) {
      @Override
      public void extractBackground(GuiGraphicsExtractor graphics) {
        ConfigScreenPlatform.this.safeBackground(graphics);
      }

      @Override
      public void extractRenderState(GuiGraphicsExtractor graphics) {
        new Canvas(graphics)
            .card(
                Math.max(0, layout.getX() - 12),
                Math.max(0, layout.getY() - 12),
                Math.min(width, layout.getX() + layout.getWidth() + 12),
                Math.min(height, layout.getY() + layout.getHeight() + 12));
        super.extractRenderState(graphics);
      }
    };
  }

  private record Canvas(GuiGraphicsExtractor graphics) implements ConfigCanvas {
    @Override
    public void fill(int x1, int y1, int x2, int y2, int color) {
      graphics.fill(x1, y1, x2, y2, color);
    }

    @Override
    public void outline(int x, int y, int width, int height, int color) {
      graphics.outline(x, y, width, height, color);
    }

    @Override
    public void centeredText(Font font, Component text, int x, int y, int color) {
      graphics.centeredText(font, text, x, y, color);
    }

    @Override
    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
      graphics.text(font, text, x, y, color, shadow);
    }
  }
}
