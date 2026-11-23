package com.qb20nh.cbbg.render;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GLContext;

/** Decodes the existing PNG cache and owns its LWJGL 2 textures. Render-thread use only. */
public final class Gl2NoiseTextures implements AutoCloseable {
    private final int[] textures;
    private final int size;

    private Gl2NoiseTextures(int[] textures, int size) {
        this.textures = textures;
        this.size = size;
    }

    public static Gl2NoiseTextures loadCache(Path directory, int size, int depth, long seed) {
        BufferedImage[] frames = Gl2NoiseImages.loadCache(directory, size, depth, seed);
        return frames == null ? null : upload(frames, size);
    }

    public static Gl2NoiseTextures upload(BufferedImage[] frames, int size) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        boolean hasPbo = GLContext.getCapabilities().OpenGL21 || GLContext.getCapabilities().GL_ARB_pixel_buffer_object;
        int unpackBuffer = hasPbo ? GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) : 0;
        int[] textures = new int[frames.length];
        try {
            if (hasPbo) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            for (int frame = 0; frame < frames.length; frame++) {
                BufferedImage image = frames[frame];
                if (image.getWidth() != size || image.getHeight() != size) {
                    throw new IllegalArgumentException("STBN frame dimensions differ");
                }
                ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * 4);
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int argb = image.getRGB(x, y);
                        pixels.put((byte) (argb >>> 16)).put((byte) (argb >>> 8))
                                .put((byte) argb).put((byte) (argb >>> 24));
                    }
                }
                pixels.flip();
                textures[frame] = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[frame]);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, size, size, 0,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new IllegalStateException("STBN texture upload failed");
            }
            return new Gl2NoiseTextures(textures, size);
        } catch (RuntimeException failure) {
            for (int texture : textures) if (texture != 0) GL11.glDeleteTextures(texture);
            throw failure;
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
            if (hasPbo) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    public int size() { return size; }
    public int depth() { return textures.length; }
    public int texture(int frame) { return textures[frame]; }

    @Override
    public void close() {
        for (int frame = 0; frame < textures.length; frame++) {
            if (textures[frame] != 0) GL11.glDeleteTextures(textures[frame]);
            textures[frame] = 0;
        }
    }
}
