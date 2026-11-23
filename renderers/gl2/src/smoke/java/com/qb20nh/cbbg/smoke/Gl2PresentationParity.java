package com.qb20nh.cbbg.smoke;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.Gl2Dither;
import com.qb20nh.cbbg.reference.DitherReference;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Reads pixels produced by Minecraft's own framebuffer quad, rather than a test quad. */
public final class Gl2PresentationParity {
    private static final int SIZE = 16;
    public interface Target {
        int texture();
        void bind();
        void draw();
        void delete();
    }
    public interface Platform {
        Target create(int width, int height);
        void active(int unit);
        void bind(int texture);
    }
    public static int run(Gl2Dither renderer, Platform platform, boolean fallback) {
        CbbgConfig.Mode mode = CbbgConfig.get().mode();
        float strength = CbbgConfig.get().strength();
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int matrix = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        boolean dither = GL11.glIsEnabled(GL11.GL_DITHER);
        IntBuffer viewport = BufferUtils.createIntBuffer(16);
        FloatBuffer clear = BufferUtils.createFloatBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, clear);
        platform.active(GL13.GL_TEXTURE0);
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        Target source = null;
        Target output = null;
        try {
            source = platform.create(SIZE, SIZE);
            output = platform.create(SIZE, SIZE);
            FloatBuffer input = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
            for (int i = 0; i < SIZE * SIZE; i++) {
                input.put(127.25f / 255f).put(127.25f / 255f).put(127.25f / 255f).put(0.25f);
            }
            input.flip();
            platform.bind(source.texture());
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, SIZE, SIZE, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, input);
            FloatBuffer stored = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, stored);
            float sourceValue = stored.get(0);
            GL11.glDisable(GL11.GL_DITHER);
            GL20.glUseProgram(0);
            int checks = 0;
            checks += check(renderer, platform, source, output, sourceValue, CbbgConfig.Mode.ENABLED, 1f, fallback);
            if (!fallback) {
                checks += check(renderer, platform, source, output, sourceValue, CbbgConfig.Mode.ENABLED, 0.5f, false);
                checks += check(renderer, platform, source, output, sourceValue, CbbgConfig.Mode.DEMO, 1f, false);
                checks += check(renderer, platform, source, output, sourceValue, CbbgConfig.Mode.DISABLED, 1f, false);
            }
            return checks;
        } finally {
            CbbgConfig.setMode(mode);
            CbbgConfig.setStrength(strength);
            if (source != null) source.delete();
            if (output != null) output.delete();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL20.glUseProgram(program);
            platform.active(GL13.GL_TEXTURE0);
            platform.bind(texture);
            platform.active(unit);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrix);
            GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            GL11.glClearColor(clear.get(0), clear.get(1), clear.get(2), clear.get(3));
            if (dither) GL11.glEnable(GL11.GL_DITHER);
        }
    }

    private static int check(Gl2Dither renderer, Platform platform, Target source, Target output, float sourceValue, CbbgConfig.Mode mode,
            float strength, boolean fallback) {
        CbbgConfig.setMode(mode);
        CbbgConfig.setStrength(strength);
        output.bind();
        GL11.glColorMask(true, true, true, true);
        GL11.glClearColor(0f, 0f, 0f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        int noiseSize = renderer.textures().size();
        ByteBuffer noise = BufferUtils.createByteBuffer(noiseSize * noiseSize * 4);
        renderer.present(SIZE, () -> {
            if (!fallback && mode != CbbgConfig.Mode.DISABLED) {
                platform.active(GL13.GL_TEXTURE1);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, noise);
                platform.active(GL13.GL_TEXTURE0);
            }
            source.draw();
        });
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        int low = 0;
        int high = 0;
        int checks = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                for (int channel = 0; channel < 4; channel++) {
                    int expected = 255; // Minecraft's presentation quad deliberately masks alpha writes.
                    if (channel < 3) {
                        float amount = fallback || mode == CbbgConfig.Mode.DISABLED ? 0f : strength;
                        int n = noise.get(((y % noiseSize) * noiseSize + x % noiseSize) * 4 + channel) & 255;
                        expected = DitherReference.shaderChannel(sourceValue, n, amount, x, SIZE,
                                mode == CbbgConfig.Mode.DEMO);
                    }
                    int actual = pixels.get((y * SIZE + x) * 4 + channel) & 255;
                    if (actual != expected) throw new AssertionError(mode + " strength=" + strength
                            + " pixel=" + x + "," + y + " channel=" + channel
                            + " expected=" + expected + " actual=" + actual);
                    checks++;
                    if (channel < 3 && actual == 127) low++;
                    if (channel < 3 && actual == 128) high++;
                }
            }
        }
        if (!fallback && mode == CbbgConfig.Mode.ENABLED && strength == 1f && (low == 0 || high == 0)) {
            throw new AssertionError("Enabled presentation did not produce both quantization levels");
        }
        if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("Presentation produced GL error");
        return checks + 1;
    }
}
