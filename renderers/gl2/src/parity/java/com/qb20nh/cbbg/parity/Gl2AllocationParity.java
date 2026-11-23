package com.qb20nh.cbbg.parity;

import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import com.qb20nh.cbbg.render.Gl2Framebuffers;
import com.qb20nh.cbbg.render.Gl2DitherProgram;
import com.qb20nh.cbbg.render.Gl2NoiseTextures;
import com.qb20nh.cbbg.render.stbn.NoiseCache;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GLContext;

/** Local GPU fixture only: no Minecraft startup or production entrypoint. */
public final class Gl2AllocationParity {
    private static int checks;

    public static void main(String[] args) throws Exception {
        scenario(PixelFormat.RGBA16F, false, GL30.GL_RGBA16F, GL30.GL_RGBA16F);
        scenario(PixelFormat.RGBA16F, true, GL11.GL_RGB8, GL30.GL_RGBA16F, GL11.GL_RGB8);
        // A new context must discard failures cached by the preceding scenario.
        scenario(PixelFormat.RGBA16F, false, GL30.GL_RGBA16F, GL30.GL_RGBA16F);
        scenario(PixelFormat.RGBA8, false, GL11.GL_RGB8, GL11.GL_RGB8);
        shaderScenario();
        noiseScenario(Paths.get(args[0]).getParent());
        String report = "{\"passed\":true,\"scenarios\":6,\"checks\":" + checks + "}";
        Files.write(Paths.get(args[0]), report.getBytes(StandardCharsets.UTF_8));
        System.out.println(report);
    }

    private static void scenario(PixelFormat requested, boolean reject16,
                                 int expected, Integer... calls) throws Exception {
        Display.setDisplayMode(new DisplayMode(64, 64));
        Display.create();
        try {
            require(GLContext.getCapabilities().OpenGL30, "Fixture requires a core framebuffer context");
            final int read = GL30.glGenFramebuffers();
            final int draw = GL30.glGenFramebuffers();
            final int texture = GL11.glGenTextures();
            try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                List<Integer> attempted = new ArrayList<>();
                Gl2Framebuffers.allocateColor(requested, GL11.GL_RGB8, format -> {
                    bindings(read, draw, texture);
                    attempted.add(format);
                    // Simulate another texture-allocation hook replacing unsupported storage.
                    int stored = reject16 && format == GL30.GL_RGBA16F ? GL11.GL_RGBA8 : format;
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, stored, 8, 8, 0,
                            GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
                });
                bindings(read, draw, texture);
                require(attempted.equals(Arrays.asList(calls)), "Wrong fallback order: " + attempted);
                require(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                        GL11.GL_TEXTURE_INTERNAL_FORMAT) == expected, "Wrong final storage");
                require(GL11.glGetError() == GL11.GL_NO_ERROR, "Leaked OpenGL error");
            } finally {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                GL30.glDeleteFramebuffers(read);
                GL30.glDeleteFramebuffers(draw);
                GL11.glDeleteTextures(texture);
            }
        } finally {
            Display.destroy();
        }
    }

    private static void bindings(int read, int draw, int texture) {
        require(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) == read, "Read framebuffer changed");
        require(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == draw, "Draw framebuffer changed");
        require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == texture, "Texture binding changed");
    }

    private static void shaderScenario() throws Exception {
        Display.setDisplayMode(new DisplayMode(64, 64));
        Display.create();
        int source = 0, noise = 0, output = 0, fbo = 0;
        try (Gl2DitherProgram shader = new Gl2DitherProgram()) {
            source = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
            nearest();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, 1, 1, 0, GL11.GL_RGBA,
                    GL11.GL_FLOAT, (java.nio.FloatBuffer) BufferUtils.createFloatBuffer(4).put(new float[] {
                            127.25f / 255f, 127.25f / 255f, 127.25f / 255f, 0.25f }).flip());
            noise = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, noise);
            nearest();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2, 1, 0, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, (ByteBuffer) BufferUtils.createByteBuffer(8).put(new byte[] {
                            0, 0, 0, -1, -1, -1, -1, -1 }).flip());
            output = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, output);
            nearest();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2, 1, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, output, 0);
            require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE,
                    "Shader output incomplete");
            GL11.glViewport(0, 0, 2, 1);
            GL11.glDisable(GL11.GL_DITHER);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, noise);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
            int previous = shader.bind(1f, 2, 1, 1f, 2, false);
            try {
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0, 0); GL11.glVertex2f(-1, -1);
                GL11.glTexCoord2f(1, 0); GL11.glVertex2f(1, -1);
                GL11.glTexCoord2f(1, 1); GL11.glVertex2f(1, 1);
                GL11.glTexCoord2f(0, 1); GL11.glVertex2f(-1, 1);
                GL11.glEnd();
            } finally {
                GL20.glUseProgram(previous);
            }
            ByteBuffer pixels = BufferUtils.createByteBuffer(8);
            GL11.glReadPixels(0, 0, 2, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            for (int pixel = 0; pixel < 2; pixel++) {
                for (int channel = 0; channel < 3; channel++) {
                    require((pixels.get(pixel * 4 + channel) & 255) == 127 + pixel,
                            "Canonical dither pixel mismatch");
                }
                require((pixels.get(pixel * 4 + 3) & 255) == 64, "Shader alpha changed");
            }
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Shader leaked OpenGL error");
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            if (source != 0) GL11.glDeleteTextures(source);
            if (noise != 0) GL11.glDeleteTextures(noise);
            if (output != 0) GL11.glDeleteTextures(output);
            Display.destroy();
        }
    }

    private static void nearest() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    }

    private static void noiseScenario(Path parent) throws Exception {
        Path cache = Files.createTempDirectory(parent, "noise-cache-");
        Path imageFile = cache.resolve(String.format(NoiseCache.IMAGE_BASE_FMT, 2, 2, 1, 0) + ".png");
        Path manifest = cache.resolve(String.format(NoiseCache.HASH_FILE_FMT, 2, 2, 1));
        Display.setDisplayMode(new DisplayMode(64, 64));
        Display.create();
        int originalTexture = GL11.glGenTextures();
        int unpack = GL15.glGenBuffers();
        try {
            require(Gl2NoiseTextures.loadCache(cache, 2, 1, 42) == null, "Missing cache accepted");
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
            int[] expected = {0xff102030, 0x80405060, 0x40708090, 0x10a0b0c0};
            image.setRGB(0, 0, 2, 2, expected, 0, 2);
            ImageIO.write(image, "png", imageFile.toFile());
            String hash = NoiseCache.calculateSHA256(Files.readAllBytes(imageFile));
            Files.write(manifest, (NoiseCache.seedHeader(42) + hash + "  " + imageFile.getFileName()
                    + "\n").getBytes(StandardCharsets.UTF_8));
            require(Gl2NoiseTextures.loadCache(cache, 2, 1, 43) == null, "Wrong cache seed accepted");
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, originalTexture);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 8);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 9);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 1);
            int uploaded;
            try (Gl2NoiseTextures noise = Gl2NoiseTextures.loadCache(cache, 2, 1, 42)) {
                require(noise != null && noise.size() == 2 && noise.depth() == 1, "Valid cache not loaded");
                require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == originalTexture, "Upload changed texture binding");
                require(GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) == unpack, "Upload changed unpack buffer");
                require(GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT) == 8, "Upload changed unpack alignment");
                require(GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH) == 9, "Upload changed unpack row length");
                require(GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS) == 1, "Upload changed unpack row skip");
                require(GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS) == 1, "Upload changed unpack pixel skip");
                uploaded = noise.texture(0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, uploaded);
                ByteBuffer pixels = BufferUtils.createByteBuffer(16);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                for (int pixel = 0; pixel < 4; pixel++) {
                    int at = pixel * 4;
                    int actual = (pixels.get(at + 3) & 255) << 24 | (pixels.get(at) & 255) << 16
                            | (pixels.get(at + 1) & 255) << 8 | pixels.get(at + 2) & 255;
                    require(actual == expected[pixel], "Noise cache GPU pixel mismatch");
                }
            }
            require(!GL11.glIsTexture(uploaded), "Noise texture leaked after close");
            Files.write(imageFile, new byte[] {1, 2, 3});
            require(Gl2NoiseTextures.loadCache(cache, 2, 1, 42) == null, "Corrupt cache accepted");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "Noise upload leaked OpenGL error");
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL15.glDeleteBuffers(unpack);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL11.glDeleteTextures(originalTexture);
            Display.destroy();
            Files.deleteIfExists(imageFile);
            Files.deleteIfExists(manifest);
            Files.deleteIfExists(cache);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
