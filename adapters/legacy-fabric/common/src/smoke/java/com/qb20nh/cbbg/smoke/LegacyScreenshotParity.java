package com.qb20nh.cbbg.smoke;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.LegacyDither;
import com.qb20nh.cbbg.render.LegacyScreenshot;
import com.qb20nh.cbbg.render.LegacyTextureState;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.util.ScreenshotUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

final class LegacyScreenshotParity {
    private static final int WIDTH = 16;
    private static final int HEIGHT = 12;

    static int run() throws Exception {
        Framebuffer main = MinecraftClient.getInstance().getFramebuffer();
        int width = main.viewportWidth;
        int height = main.viewportHeight;
        CbbgConfig saved = CbbgConfig.get();
        Framebuffer other = null;
        int checks = 0;
        try {
            CbbgConfig.setStrength(1f);
            main.resize(WIDTH, HEIGHT);
            other = new Framebuffer(4, 4, false);
            FloatBuffer data = BufferUtils.createFloatBuffer(WIDTH * HEIGHT * 4);
            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                data.put(base(x, y, 0) / 255f).put(base(x, y, 1) / 255f).put(base(x, y, 2) / 255f).put(0.25f);
            }
            data.flip();
            LegacyTextureState.active(GL13.GL_TEXTURE0);
            LegacyTextureState.bind(main.colorAttachment);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, WIDTH, HEIGHT, 0, GL11.GL_RGBA, GL11.GL_FLOAT, data);
            FloatBuffer stored = BufferUtils.createFloatBuffer(WIDTH * HEIGHT * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, stored);
            int noiseSize = LegacyDither.textures().size();
            ByteBuffer noise = BufferUtils.createByteBuffer(noiseSize * noiseSize * 4);
            LegacyTextureState.bind(LegacyDither.textures().texture(0));
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, noise);
            for (CbbgConfig.Mode mode : CbbgConfig.Mode.values()) {
                CbbgConfig.setMode(mode);
                LegacyDither.resourcesReloaded(); // A fixed noise frame makes the CPU comparison exact.
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, other.fbo);
                long before = LegacyDither.presentations();
                Path directory = Files.createTempDirectory("cbbg-screenshot-" + mode + "-");
                ScreenshotUtils.saveScreenshot(directory.toFile(), WIDTH, HEIGHT, main);
                int error = GL11.glGetError();
                if (error != GL11.GL_NO_ERROR) throw new AssertionError(mode + " screenshot GL error " + error);
                java.io.File[] files = directory.resolve("screenshots").toFile().listFiles();
                if (files == null || files.length != 1) throw new AssertionError("Vanilla screenshot was not saved");
                BufferedImage image = ImageIO.read(files[0]);
                if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT) throw new AssertionError("Screenshot dimensions changed");
                if (GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) != main.fbo
                        || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != other.fbo) {
                    throw new AssertionError("Screenshot changed independent framebuffer bindings");
                }
                if (LegacyDither.presentations() - before != (mode == CbbgConfig.Mode.DISABLED ? 0 : 1)) {
                    throw new AssertionError("Screenshot applied dithering more or less than once");
                }
                int changed = 0;
                for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                    int rgb = image.getRGB(x, HEIGHT - y - 1);
                    for (int c = 0; c < 3; c++) {
                        double amount = mode == CbbgConfig.Mode.DISABLED || mode == CbbgConfig.Mode.DEMO && x < WIDTH / 2 ? 0 : 1;
                        int n = noise.get(((y % noiseSize) * noiseSize + x % noiseSize) * 4 + c) & 255;
                        double source = stored.get((y * WIDTH + x) * 4 + c) * 255.0;
                        int expected = (int) Math.floor(source + amount * (n / 255.0 - 0.5) + 0.5);
                        if (mode == CbbgConfig.Mode.DEMO && x == WIDTH / 2) expected = 255 - expected;
                        int actual = rgb >> (16 - c * 8) & 255;
                        if (actual != expected) throw new AssertionError(mode + " screenshot pixel " + x + "," + y
                                + " channel " + c + " expected " + expected + " got " + actual);
                        if (actual != (int) base(x, y, c)) changed++;
                        checks++;
                    }
                    if ((rgb >>> 24) != 255) throw new AssertionError("Vanilla PNG alpha changed");
                    checks++;
                }
                if (mode == CbbgConfig.Mode.ENABLED && changed == 0) throw new AssertionError("Screenshot had no dither variance");
                checks += 5;
            }
            CbbgConfig.setMode(CbbgConfig.Mode.ENABLED);
            long presentations = LegacyDither.presentations();
            final boolean[] called = {false};
            LegacyScreenshot.read(other, () -> called[0] = true);
            if (!called[0] || LegacyDither.presentations() != presentations) throw new AssertionError("Custom screenshot target was intercepted");
            final int[] allocated = new int[2];
            int pack = GL15.glGenBuffers();
            int unpack = GL15.glGenBuffers();
            int oldPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            int oldUnpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
            GL11.glViewport(3, 4, 5, 6);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(0, 0, 1, 1);
            LegacyTextureState.active(GL13.GL_TEXTURE1);
            try {
                try {
                    LegacyScreenshot.read(main, () -> {
                        allocated[0] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                        allocated[1] = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
                        if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT) != GL11.GL_RGBA8) {
                            throw new AssertionError("Readback target is not RGBA8");
                        }
                        if (GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING) != 0
                                || GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) != 0) {
                            throw new AssertionError("Pixel buffers were not isolated");
                        }
                        throw new IllegalStateException("injected readback failure");
                    });
                    throw new AssertionError("Readback failure was swallowed");
                } catch (IllegalStateException expected) {
                    if (!"injected readback failure".equals(expected.getMessage())) throw expected;
                }
                if (GL11.glIsTexture(allocated[0]) || GL30.glIsFramebuffer(allocated[1])) {
                    throw new AssertionError("Failed readback leaked GPU objects");
                }
                IntBuffer viewport = BufferUtils.createIntBuffer(16);
                GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
                if (viewport.get(0) != 3 || viewport.get(1) != 4 || viewport.get(2) != 5 || viewport.get(3) != 6
                        || !GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
                        || GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) != GL13.GL_TEXTURE1
                        || GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING) != pack
                        || GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) != unpack
                        || GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) != main.fbo
                        || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != other.fbo) {
                    throw new AssertionError("Failed readback did not restore GL state");
                }
            } finally {
                LegacyTextureState.active(GL13.GL_TEXTURE0);
                GL11.glPopAttrib();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPack);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, oldUnpack);
                GL15.glDeleteBuffers(pack);
                GL15.glDeleteBuffers(unpack);
            }
            if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("Screenshot GL error");
            return checks + 6;
        } finally {
            CbbgConfig.setMode(saved.mode());
            CbbgConfig.setStrength(saved.strength());
            if (other != null) other.delete();
            main.resize(width, height);
            main.bind(true);
        }
    }

    private static float base(int x, int y, int channel) {
        return channel == 0 ? (y % 2 == 0 ? 63.25f : 127.25f)
                : channel == 1 ? (x % 2 == 0 ? 31.25f : 191.25f) : 95.25f;
    }
}
