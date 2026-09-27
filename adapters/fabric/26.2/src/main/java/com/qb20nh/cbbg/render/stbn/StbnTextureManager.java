package com.qb20nh.cbbg.render.stbn;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Manages the lifecycle of the STBN noise texture, including GL format overrides and recreation on
 * configuration changes.
 */
@NullMarked
public class StbnTextureManager {

  private @Nullable GpuTexture texture;
  private @Nullable GpuTextureView view;

  // Track parameters to detect changes
  private int currentWidth = -1;
  private int currentHeight = -1;

  public @Nullable GpuTextureView getView() {
    return view;
  }

  public @Nullable GpuTexture getTexture() {
    return texture;
  }

  public boolean isReady() {
    return texture != null && !texture.isClosed() && view != null && !view.isClosed();
  }

  public void ensureTexture(int width, int height) {
    if (width <= 0 || height <= 0) return;

    // Check if recreation is needed
    boolean dimChanged = width != currentWidth || height != currentHeight;

    if (texture != null && !texture.isClosed() && dimChanged) {
      close();
    }

    if (texture == null || texture.isClosed() || view == null || view.isClosed()) {
      createTexture(width, height);
    }
  }

  private void createTexture(int width, int height) {
    GpuTexture created =
        RenderSystem.getDevice()
            .createTexture(
                () -> "cbbg / STBN",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                1);
    texture = created;
    view = RenderSystem.getDevice().createTextureView(created);

    // Update tracking
    currentWidth = width;
    currentHeight = height;
  }

  public void close() {
    if (view != null) {
      view.close();
      view = null;
    }
    if (texture != null) {
      texture.close();
      texture = null;
    }
  }
}
