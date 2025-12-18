package com.qb20nh.cbbg.render.stbn;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import org.jspecify.annotations.NonNull;

/**
 * Manages the lifecycle of the STBN noise texture, including GL format overrides and recreation on
 * configuration changes.
 */
public class StbnTextureManager {

    private GpuTexture texture;
    private GpuTextureView view;

    // Track parameters to detect changes
    private int currentWidth = -1;
    private int currentHeight = -1;

    public GpuTextureView getView() {
        return view;
    }

    public GpuTexture getTexture() {
        return texture;
    }

    public boolean isReady() {
        return texture != null && !texture.isClosed() && view != null && !view.isClosed();
    }

    public void ensureTexture(int width, int height) {
        if (width <= 0 || height <= 0)
            return;

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
        @NonNull
        GpuTexture created =
                (@NonNull GpuTexture) RenderSystem.getDevice().createTexture(() -> "cbbg / STBN",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        TextureFormat.RGBA8, width, height, 1, 1);
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
