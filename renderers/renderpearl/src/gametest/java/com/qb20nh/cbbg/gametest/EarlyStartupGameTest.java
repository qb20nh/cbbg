package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

/** Tests the processed JAR through Minecraft commands, draws and cache files. */
public final class EarlyStartupGameTest implements FabricClientGameTest {
    public static final AtomicLong firstDrawMillis = new AtomicLong();
    public static final AtomicLong draws = new AtomicLong();
    public static final Set<CompiledRenderPipeline> ditherPipelines =
            Collections.newSetFromMap(new WeakHashMap<>());

    public static void remember(RenderPipeline source, CompiledRenderPipeline compiled) {
        String name = source.getLocation().toString();
        if (compiled != null && (name.equals("cbbg:pipeline/dither") || name.equals("cbbg:pipeline/demo"))) {
            ditherPipelines.add(compiled);
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        long titleMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        Path game = FabricLoader.getInstance().getGameDir();
        Path config = FabricLoader.getInstance().getConfigDir().resolve("cbbg.json");
        String backend = System.getProperty("cbbg.test.backend");
        JsonObject settings = readConfig(config);
        int size = settings.get("stbnSize").getAsInt();
        int depth = settings.get("stbnDepth").getAsInt();
        long seed = settings.get("stbnSeed").getAsLong();
        context.runOnClient(client -> {
            String actual = RenderSystem.getDevice().getDeviceInfo().backendName();
            if (!actual.equalsIgnoreCase(backend)) {
                throw new AssertionError("Requested " + backend + ", received " + actual);
            }
        });
        context.waitFor(client -> cacheValid(game, size, depth, seed), 6000);
        long cacheReadyMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        context.waitFor(client -> firstDrawMillis.get() != 0, 600);
        JsonObject result = new JsonObject();
        result.addProperty("backend", backend);
        result.addProperty("titleMillis", titleMillis);
        result.addProperty("cacheReadyMillis", cacheReadyMillis);
        result.addProperty("firstDrawMillis", firstDrawMillis.get());
        result.addProperty("size", size);
        result.addProperty("depth", depth);
        result.addProperty("seed", seed);
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
                context.waitFor(client -> cacheValid(game, 16, 8, 42), 600);
                JsonObject changed = readConfig(config);
                if (changed.get("stbnSize").getAsInt() != 16
                        || changed.get("stbnDepth").getAsInt() != 8
                        || changed.get("stbnSeed").getAsLong() != 42) {
                    throw new AssertionError("Noise settings were not saved");
                }
                command(context, "format set rgba32f");
                context.waitFor(client -> client.gameRenderer.mainRenderTarget()
                        .getColorTexture().getFormat() == GpuFormat.RGBA32_FLOAT, 600);
                long afterFormat = draws.get();
                context.waitFor(client -> draws.get() > afterFormat, 600);
                context.runOnClient(client -> {
                    TextureTarget external = new TextureTarget("External framebuffer", 16, 16,
                            GpuFormat.RGBA8_UNORM, null);
                    try {
                        external.resize(32, 24);
                        if (external.getColorTexture().getFormat() != GpuFormat.RGBA8_UNORM) {
                            throw new AssertionError("CBBG changed an external framebuffer format");
                        }
                    } finally {
                        external.destroyBuffers();
                    }
                });
                context.takeScreenshot("early-lifecycle-" + backend);
            }
        }
        try {
            Files.writeString(game.resolve("early-startup-result.json"), result + "\n");
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void command(ClientGameTestContext context, String suffix) {
        context.runOnClient(client -> client.getConnection().sendCommand("cbbg " + suffix));
    }

    private static JsonObject readConfig(Path path) {
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean cacheValid(Path game, int size, int depth, long seed) {
        Path cache = game.resolve(".cbbg");
        Path manifest = cache.resolve("stbn_" + size + "x" + size + "x" + depth + ".sha256");
        try {
            if (!Files.isRegularFile(manifest)) return false;
            var lines = Files.readAllLines(manifest);
            if (lines.size() != depth + 1 || !lines.getFirst().equals("# seed " + seed)) return false;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines.subList(1, lines.size())) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length != 2) return false;
                Path image = cache.resolve(parts[1]);
                if (!Files.isRegularFile(image) || !HexFormat.of().formatHex(digest.digest(Files.readAllBytes(image)))
                        .equals(parts[0])) return false;
            }
            return true;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
