package com.qb20nh.cbbg.render;

import java.nio.ByteBuffer;
import java.util.function.IntConsumer;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/** Converts the main float target before vanilla's texture readback and PNG encoding. */
public final class Gl2Screenshot {
    private Gl2Screenshot() {}

    public static void read(int source, int width, int height, Gl2Dither renderer,
            IntConsumer activeTexture, IntConsumer bindTexture, Runnable read) {
        ContextCapabilities caps = GLContext.getCapabilities();
        boolean separate = caps.OpenGL30 || caps.GL_ARB_framebuffer_object || caps.GL_EXT_framebuffer_blit;
        int draw = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int input = separate ? GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) : draw;
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int matrix = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        boolean pixelBuffers = GLContext.getCapabilities().OpenGL21 || GLContext.getCapabilities().GL_ARB_pixel_buffer_object;
        int pack = pixelBuffers ? GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING) : 0;
        int unpack = pixelBuffers ? GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) : 0;
        activeTexture.accept(GL13.GL_TEXTURE0);
        int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        int texture = 0;
        int framebuffer = 0;
        try {
            if (pixelBuffers) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            }
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, GL11.GL_FALSE);
            texture = GL11.glGenTextures();
            bindTexture.accept(texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height,
                    0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            framebuffer = Gl2Framebuffers.create(caps);
            Gl2Framebuffers.bind(caps, GL30.GL_FRAMEBUFFER, framebuffer);
            Gl2Framebuffers.attach(caps, texture);
            if (Gl2Framebuffers.status(caps) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete RGBA8 screenshot target");
            }
            GL11.glViewport(0, 0, width, height);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_DITHER);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL11.glColorMask(true, true, true, true);
            for (int i = 1; i < GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS); i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_REPLACE);
            GL20.glUseProgram(0);
            bindTexture.accept(source);
            renderer.present(width, () -> {
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0, 0); GL11.glVertex2f(-1, -1);
                GL11.glTexCoord2f(1, 0); GL11.glVertex2f(1, -1);
                GL11.glTexCoord2f(1, 1); GL11.glVertex2f(1, 1);
                GL11.glTexCoord2f(0, 1); GL11.glVertex2f(-1, 1);
                GL11.glEnd();
            });
            bindTexture.accept(texture);
            read.run();
        } finally {
            if (framebuffer != 0) Gl2Framebuffers.delete(caps, framebuffer);
            if (texture != 0) GL11.glDeleteTextures(texture);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            // Read/draw buffer state in the attribute stack belongs to these FBOs.
            if (separate) {
                Gl2Framebuffers.bind(caps, GL30.GL_DRAW_FRAMEBUFFER, draw);
                Gl2Framebuffers.bind(caps, GL30.GL_READ_FRAMEBUFFER, input);
            } else Gl2Framebuffers.bind(caps, GL30.GL_FRAMEBUFFER, draw);
            GL11.glPopAttrib();
            GL11.glPopClientAttrib();
            if (pixelBuffers) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
            }
            GL11.glMatrixMode(matrix);
            GL20.glUseProgram(program);
            bindTexture.accept(binding);
            activeTexture.accept(unit);
        }
    }
}
