package com.qb20nh.cbbg.debug;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * Small OpenGL helpers used by cbbg for debug/verification.
 *
 * <p>
 * Callers must already be on the render thread (or init thread) as appropriate.
 */
public final class CbbgGlUtil {

    private CbbgGlUtil() {}

    /**
     * Returns the {@code GL_TEXTURE_INTERNAL_FORMAT} for mip level 0 of a 2D texture.
     */
    public static int getTextureInternalFormat2D(int textureId) {
        int prev = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                    GL11.GL_TEXTURE_INTERNAL_FORMAT);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        }
    }
}
