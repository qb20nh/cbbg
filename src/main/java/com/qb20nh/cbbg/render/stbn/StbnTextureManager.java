package com.qb20nh.cbbg.render.stbn;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;

/**
 * Manages the lifecycle of the STBN noise texture, including GL format overrides and recreation on
 * configuration changes.
 */
public class StbnTextureManager {

    private int textureId = -1;

    // Track parameters to detect changes
    private int currentWidth = -1;
    private int currentHeight = -1;

    public int getTextureId() {
        return textureId;
    }

    public boolean isReady() {
        return textureId > 0;
    }

    public void ensureTexture(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }

        // Check if recreation is needed
        boolean dimChanged = width != currentWidth || height != currentHeight;

        if (textureId > 0 && dimChanged) {
            close();
        }

        if (textureId <= 0) {
            createTexture(width, height);
        }
    }

    private void createTexture(int width, int height) {
        RenderSystem.assertOnRenderThreadOrInit();

        textureId = TextureUtil.generateTextureId();
        GlStateManager._bindTexture(textureId);

        // Allocate RGBA8 storage for the noise atlas.
        TextureUtil.prepareImage(textureId, 0, width, height);

        // Stable, crisp noise sampling.
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

        // Update tracking
        currentWidth = width;
        currentHeight = height;
    }

    public void uploadFrame(NativeImage frame) {
        if (frame == null) {
            return;
        }
        ensureTexture(frame.getWidth(), frame.getHeight());
        if (textureId <= 0) {
            return;
        }

        RenderSystem.assertOnRenderThreadOrInit();
        GlStateManager._bindTexture(textureId);
        frame.upload(0, 0, 0, false);
    }

    public void close() {
        if (textureId > 0) {
            TextureUtil.releaseTextureId(textureId);
            textureId = -1;
        }
        currentWidth = -1;
        currentHeight = -1;
    }
}
