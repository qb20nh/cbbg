package com.qb20nh.cbbg.config.gui;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
    if (Minecraft.getInstance().level != null) {
      renderTransparentBackground(graphics);
    } else {
      renderPanorama(graphics, delta);
      renderMenuBackground(graphics);
    }
  }

  @Override
  public void render(GuiGraphics graphics, int x, int y, float delta) {
    drawConfig(new Canvas(graphics));
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
        new Canvas(graphics)
            .card(
                Math.max(0, width / 2 - 172),
                40,
                Math.min(width, width / 2 + 172),
                Math.max(40, height - 24));
        super.render(graphics, x, y, delta);
      }
    };
  }

  private record Canvas(GuiGraphics graphics) implements ConfigCanvas {
    @Override
    public void fill(int x1, int y1, int x2, int y2, int color) {
      graphics.fill(x1, y1, x2, y2, color);
    }

    @Override
    public void outline(int x, int y, int width, int height, int color) {
      graphics.renderOutline(x, y, width, height, color);
    }

    @Override
    public void centeredText(Font font, Component text, int x, int y, int color) {
      graphics.drawCenteredString(font, text, x, y, color);
    }

    @Override
    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
      graphics.drawString(font, text, x, y, color, shadow);
    }
  }
}
