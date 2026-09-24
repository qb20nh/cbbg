package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

/** Batched presentation microbenchmark, not a world FPS benchmark. */
public final class DsaBenchmarkGameTest implements FabricClientGameTest {
    private static final int WARMUP = 128;
    private static final int SAMPLES = 30;
    private static final int BATCH = 32;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitFor(client -> DitherController.isReady() && client.gui.overlay() == null, 600);
        context.runOnClient(client -> {
            String mode = System.getProperty("cbbg.test.dsa", "auto");
            var info = RenderSystem.getDevice().getDeviceInfo();
            boolean selected = info.underlyingExtensions().contains("GL_ARB_direct_state_access");
            if (!info.backendName().equalsIgnoreCase("opengl") || selected != mode.equals("auto")) {
                throw new AssertionError("Requested DSA path was not selected");
            }
            JsonObject report = new JsonObject();
            report.addProperty("scope", "batched CBBG presentation microbenchmark");
            report.addProperty("dsaMode", mode);
            report.addProperty("selectedDirectStateAccess", selected);
            report.addProperty("warmupPasses", WARMUP);
            report.addProperty("samples", SAMPLES);
            report.addProperty("passesPerSample", BATCH);
            report.addProperty("noiseSize", CbbgConfig.get().stbnSize());
            report.addProperty("noiseDepth", CbbgConfig.get().stbnDepth());
            report.addProperty("noiseSeed", CbbgConfig.get().stbnSeed());
            JsonArray workloads = new JsonArray();
            try {
                for (int[] size : new int[][] {{960, 540}, {1920, 1080}}) {
                    workloads.add(measure(size[0], size[1]));
                }
            } finally {
                DitherController.resetAfterToggle();
                DitherController.beginFrame();
            }
            report.add("workloads", workloads);
            report.addProperty("sumOfHeapPoolPeaksBytes", ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(pool -> pool.getType() == MemoryType.HEAP)
                    .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum());
            try {
                Files.writeString(Path.of(System.getProperty("cbbg.test.evidence"))
                        .resolve("dsa-benchmark.json"), report.toString() + "\n");
            } catch (java.io.IOException failure) {
                throw new AssertionError("Could not write benchmark evidence", failure);
            }
        });
    }

    private static JsonObject measure(int width, int height) {
        var source = new TextureTarget("CBBG benchmark source", width, height, GpuFormat.RGBA32_FLOAT, null);
        int startQuery = GL15C.glGenQueries();
        int endQuery = GL15C.glGenQueries();
        var texture = source.getColorTexture();
        try {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture,
                    new Vector4f(127.25f / 255, 127.25f / 255, 127.25f / 255, 0.375f));
            for (int i = 0; i < WARMUP; i++) present(source);
            JsonArray samples = new JsonArray();
            for (int sample = 0; sample < SAMPLES; sample++) {
                GL11C.glFinish();
                GL33C.glQueryCounter(startQuery, GL33C.GL_TIMESTAMP);
                long start = System.nanoTime();
                for (int pass = 0; pass < BATCH; pass++) present(source);
                long cpuNanos = System.nanoTime() - start;
                GL33C.glQueryCounter(endQuery, GL33C.GL_TIMESTAMP);
                long gpuStart = GL33C.glGetQueryObjectui64(startQuery, GL15C.GL_QUERY_RESULT);
                long gpuEnd = GL33C.glGetQueryObjectui64(endQuery, GL15C.GL_QUERY_RESULT);
                if (gpuEnd <= gpuStart || cpuNanos <= 0) throw new AssertionError("Invalid timing result");
                JsonObject measurement = new JsonObject();
                measurement.addProperty("cpuSubmissionNanos", cpuNanos);
                measurement.addProperty("gpuElapsedNanos", gpuEnd - gpuStart);
                samples.add(measurement);
            }
            JsonObject workload = new JsonObject();
            workload.addProperty("width", width);
            workload.addProperty("height", height);
            workload.addProperty("sourceAndOutputTextureBytes", (long) width * height * 20);
            workload.add("measurements", samples);
            return workload;
        } finally {
            GL11C.glFinish();
            GL15C.glDeleteQueries(startQuery);
            GL15C.glDeleteQueries(endQuery);
            source.destroyBuffers();
            if (!texture.isClosed()) throw new AssertionError("Benchmark source leaked");
        }
    }

    private static void present(TextureTarget source) {
        var input = source.getColorTextureView();
        if (DitherController.present(input) == input) throw new AssertionError("Benchmark effect fell back");
    }
}
