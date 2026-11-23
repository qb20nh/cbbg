package com.qb20nh.cbbg.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/** GLSL 1.20 wrapper around the canonical shader; the caller owns textures and draw state. */
public final class Gl2DitherProgram implements AutoCloseable {
    private int program;
    private final int strength;
    private final int noiseSize;
    private final int coordinateScale;
    private final int sourceWidth;
    private final int demo;

    public Gl2DitherProgram() {
        int vertex = compile(GL20.GL_VERTEX_SHADER, resource("core/gl2_dither.vsh"));
        int fragment = 0;
        int linked = 0;
        try {
            fragment = compile(GL20.GL_FRAGMENT_SHADER, "#version 120\n#define texture texture2D\n"
                    + resource("include/dither.glsl") + "\n" + resource("core/gl2_dither.fsh"));
            linked = GL20.glCreateProgram();
            GL20.glAttachShader(linked, vertex);
            GL20.glAttachShader(linked, fragment);
            GL20.glLinkProgram(linked);
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Legacy dither link failed: " + GL20.glGetProgramInfoLog(linked, 8192));
            }
            program = linked;
        } catch (RuntimeException failure) {
            if (linked != 0) GL20.glDeleteProgram(linked);
            throw failure;
        } finally {
            GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
        strength = GL20.glGetUniformLocation(program, "Strength");
        noiseSize = GL20.glGetUniformLocation(program, "NoiseSize");
        coordinateScale = GL20.glGetUniformLocation(program, "CoordScale");
        sourceWidth = GL20.glGetUniformLocation(program, "SourceWidth");
        demo = GL20.glGetUniformLocation(program, "Demo");
        int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(program);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "DiffuseSampler"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "NoiseSampler"), 1);
        GL20.glUseProgram(previous);
    }

    /** Returns the previous program for restoration after the caller's draw. */
    public int bind(float amount, int noiseWidth, int noiseHeight, float scale, int width, boolean showDemo) {
        if (program == 0) throw new IllegalStateException("Legacy dither program is closed");
        int previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(program);
        GL20.glUniform1f(strength, amount);
        GL20.glUniform2f(noiseSize, noiseWidth, noiseHeight);
        GL20.glUniform2f(coordinateScale, scale, scale);
        GL20.glUniform1f(sourceWidth, width);
        GL20.glUniform1f(demo, showDemo ? 1f : 0f);
        return previous;
    }

    @Override
    public void close() {
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 8192);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Legacy dither compilation failed: " + log);
        }
        return shader;
    }

    private static String resource(String name) {
        try (InputStream input = Gl2DitherProgram.class.getResourceAsStream("/assets/cbbg/shaders/" + name)) {
            if (input == null) throw new IllegalStateException("Missing legacy shader resource: " + name);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load legacy shader: " + name, failure);
        }
    }
}
