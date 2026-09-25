package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Tests the packaged mod through saved settings, cache files and client commands. */
public final class EarlyStartupGameTest implements FabricClientGameTest {
    public static final java.util.concurrent.atomic.AtomicLong firstDrawMillis =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong draws =
            new java.util.concurrent.atomic.AtomicLong();
    @Override
    public void runTest(ClientGameTestContext context) {
        long titleMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        Path game = FabricLoader.getInstance().getGameDir();
        Path config = FabricLoader.getInstance().getConfigDir().resolve("cbbg.json");
        String backend = System.getProperty("cbbg.test.backend");
        if (!FabricLoader.getInstance().isModLoaded("cbbg")) throw new AssertionError("CBBG missing");
        if (Boolean.getBoolean("cbbg.test.earlyAdapter")
                && FabricLoader.getInstance().isModLoaded("asmfabricloader")) {
            throw new AssertionError("AsmFabricLoader is still installed");
        }
        JsonObject settings = readConfig(config);
        int size = settings.get("stbnSize").getAsInt();
        int depth = settings.get("stbnDepth").getAsInt();
        context.runOnClient(client -> {
            String actual = RenderSystem.getDevice().getDeviceInfo().backendName();
            if (!actual.equalsIgnoreCase(backend)) {
                throw new AssertionError("Requested " + backend + ", received " + actual);
            }
        });
        context.waitFor(client -> cacheValid(game, size, depth), 6000);
        context.waitFor(client -> firstDrawMillis.get() != 0, 600);
        context.waitTicks(5);
        JsonObject result = new JsonObject();
        result.addProperty("backend", backend);
        result.addProperty("titleMillis", titleMillis);
        result.addProperty("cacheReadyMillis", ManagementFactory.getRuntimeMXBean().getUptime());
        result.addProperty("firstDrawMillis", firstDrawMillis.get());
        result.addProperty("size", size);
        result.addProperty("depth", depth);
        result.addProperty("seed", settings.get("stbnSeed").getAsLong());
        context.runOnClient(client -> {
            var info = RenderSystem.getDevice().getDeviceInfo();
            result.addProperty("device", info.name());
            result.addProperty("driver", info.driverInfo());
        });
        context.takeScreenshot("early-startup-" + backend);

        if (Boolean.getBoolean("cbbg.test.lifecycle")) {
            try (var world = context.worldBuilder().create()) {
                world.getConnection().waitForChunksRender();
                for (String mode : new String[] {"disabled", "enabled", "demo"}) {
                    command(context, "mode set " + mode);
                    context.waitTicks(3);
                    if (!readConfig(config).get("mode").getAsString().equalsIgnoreCase(mode)) {
                        throw new AssertionError("Mode was not saved: " + mode);
                    }
                    long count = draws.get();
                    if (mode.equals("disabled")) {
                        context.waitTicks(3);
                        if (draws.get() != count) throw new AssertionError("CBBG drew while disabled");
                    } else {
                        context.waitFor(client -> draws.get() > count, 600);
                    }
                }
                var reload = context.computeOnClient(client -> client.reloadResourcePacks());
                context.waitFor(client -> reload.isDone(), 600);
                reload.join();
                context.waitFor(client -> client.gui.overlay() == null, 600);
                long afterReload = draws.get();
                context.waitFor(client -> draws.get() > afterReload, 600);
                command(context, "stbn size 16");
                command(context, "stbn depth 8");
                command(context, "stbn seed 42");
                command(context, "stbn generate");
                context.waitFor(client -> cacheValid(game, 16, 8), 600);
                context.waitTicks(5);
                JsonObject changed = readConfig(config);
                if (changed.get("stbnSize").getAsInt() != 16
                        || changed.get("stbnDepth").getAsInt() != 8
                        || changed.get("stbnSeed").getAsLong() != 42) {
                    throw new AssertionError("Noise settings were not saved");
                }
                context.takeScreenshot("early-lifecycle-" + backend);
                command(context, "format set rgba32f");
                context.waitFor(client -> hasFormat(
                        client.gameRenderer.mainRenderTarget().getColorTexture(),
                        GpuFormat.RGBA32_FLOAT, GL30.GL_RGBA32F), 600);
                long afterFormat = draws.get();
                context.waitFor(client -> draws.get() > afterFormat, 600);
                context.runOnClient(client -> {
                    TextureTarget external = new TextureTarget("External framebuffer", 16, 16,
                            false, GpuFormat.RGBA8_UNORM);
                    try {
                        external.resize(32, 24);
                        if (!hasFormat(external.getColorTexture(), GpuFormat.RGBA8_UNORM,
                                GL11.GL_RGBA8)) {
                            throw new AssertionError("CBBG changed an external framebuffer format");
                        }
                    } finally {
                        external.destroyBuffers();
                    }
                });
            }
        }
        try {
            Files.writeString(game.resolve("early-startup-result.json"), result + "\n");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void command(ClientGameTestContext context, String suffix) {
        context.runOnClient(client -> client.getConnection().sendCommand("cbbg " + suffix));
    }

    private static boolean hasFormat(GpuTexture texture, GpuFormat format, int glFormat) {
        if (!(texture instanceof GlTexture gl)) return texture.getFormat() == format;
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, gl.glId());
            return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                    GL11.GL_TEXTURE_INTERNAL_FORMAT) == glFormat;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    private static JsonObject readConfig(Path config) {
        try (var reader = Files.newBufferedReader(config)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static boolean cacheValid(Path game, int size, int depth) {
        Path cache = game.resolve(".cbbg");
        Path manifest = cache.resolve("stbn_" + size + "x" + size + "x" + depth + ".sha256");
        try {
            if (!Files.isRegularFile(manifest)) return false;
            var lines = Files.readAllLines(manifest);
            if (lines.size() != depth) return false;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length != 2) return false;
                Path image = cache.resolve(parts[1]);
                if (!Files.isRegularFile(image) || !HexFormat.of().formatHex(digest.digest(Files.readAllBytes(image)))
                        .equals(parts[0])) return false;
            }
            return true;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
