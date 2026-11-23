package com.qb20nh.cbbg.smoke;

import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import com.qb20nh.cbbg.render.Gl2Framebuffers;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Real storage checks with an allocator that deliberately rejects float formats. */
public final class Gl2AllocationParity {
    @SuppressWarnings("unchecked")
    public static int run() throws Exception {
        Field field = Gl2Framebuffers.class.getDeclaredField("SUPPORTED");
        field.setAccessible(true);
        Map<PixelFormat, Boolean> supported = (Map<PixelFormat, Boolean>) field.get(null);
        Map<PixelFormat, Boolean> before = new EnumMap<>(PixelFormat.class);
        before.putAll(supported);
        int textureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int readBinding = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int drawBinding = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int texture = GL11.glGenTextures();
        int read = GL30.glGenFramebuffers(), draw = GL30.glGenFramebuffers();
        int checks = 0;
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            for (int rejected = 0; rejected <= 2; rejected++) {
                supported.clear();
                final int failures = rejected;
                java.util.List<Integer> attempts = new java.util.ArrayList<>();
                Gl2Framebuffers.allocateColor(PixelFormat.RGBA32F, GL11.GL_RGBA8, format -> {
                    attempts.add(format);
                    int storage = format == GL30.GL_RGBA32F && failures >= 1
                            || format == GL30.GL_RGBA16F && failures >= 2 ? GL11.GL_RGBA8 : format;
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, storage, 8, 8, 0,
                            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                });
                int expected = rejected == 0 ? GL30.GL_RGBA32F
                        : rejected == 1 ? GL30.GL_RGBA16F : GL11.GL_RGBA8;
                if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT) != expected) {
                    throw new AssertionError("Wrong allocation fallback storage");
                }
                checks++;
                java.util.List<Integer> sequence = java.util.Arrays.asList(GL30.GL_RGBA32F, GL30.GL_RGBA16F, GL11.GL_RGBA8);
                if (!attempts.equals(sequence.subList(0, rejected + 1))) throw new AssertionError("Wrong fallback order: " + attempts);
                checks++;
                if (GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) != read
                        || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != draw
                        || GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != texture) {
                    throw new AssertionError("Format probe changed caller bindings");
                }
                checks++;
                if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("Allocation fixture GL error");
                checks++;
            }
            return checks;
        } finally {
            supported.clear();
            supported.putAll(before);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBinding);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBinding);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureBinding);
            GL30.glDeleteFramebuffers(read);
            GL30.glDeleteFramebuffers(draw);
            GL11.glDeleteTextures(texture);
        }
    }
}
