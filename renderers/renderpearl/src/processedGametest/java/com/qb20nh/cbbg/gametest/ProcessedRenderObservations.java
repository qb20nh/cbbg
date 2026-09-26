package com.qb20nh.cbbg.gametest;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Observes Minecraft's actual CBBG draw calls without depending on mod internals. */
public final class ProcessedRenderObservations {
    private static final Set<CompiledRenderPipeline> PIPELINES = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private static final AtomicLong DRAWS = new AtomicLong();
    private static final AtomicLong FIRST_DRAW = new AtomicLong();
    private static CompiledRenderPipeline selectedPipeline;
    private static GpuTextureView lastDitherOutput;
    private static GpuTextureView lastDitherNoise;
    private static long ditherSelections;
    private static Consumer<GpuTextureView> noiseObserver;
    private static long observedDraws;

    private ProcessedRenderObservations() {}

    public static void remember(RenderPipeline source, CompiledRenderPipeline compiled) {
        String name = source.getLocation().toString();
        if (compiled != null && (name.equals("cbbg:pipeline/dither") || name.equals("cbbg:pipeline/demo"))) {
            PIPELINES.add(compiled);
        }
    }

    public static boolean isDither(CompiledRenderPipeline pipeline) {
        return PIPELINES.contains(pipeline);
    }

    /** Read and written on the render thread, immediately around setPipeline. */
    public static CompiledRenderPipeline selectedPipeline() {
        return selectedPipeline;
    }

    public static void select(CompiledRenderPipeline pipeline) {
        selectedPipeline = pipeline;
    }

    /** The real color attachment selected for the most recent dither or demo draw. */
    public static void ditherOutput(GpuTextureView output) {
        lastDitherOutput = output;
        ditherSelections++;
    }

    public static GpuTextureView lastDitherOutput() {
        return lastDitherOutput;
    }

    /** The texture view successfully bound as NoiseSampler by the most recent dither pass. */
    public static void ditherNoise(GpuTextureView noise) {
        lastDitherNoise = noise;
    }

    public static GpuTextureView lastDitherNoise() {
        return lastDitherNoise;
    }

    public static long ditherSelections() {
        return ditherSelections;
    }

    /** Installed and cleared on the render thread; observes completed presentation frames. */
    public static void setNoiseObserver(Consumer<GpuTextureView> observer) {
        noiseObserver = observer;
        observedDraws = DRAWS.get();
    }

    public static void afterFrame() {
        long draws = DRAWS.get();
        if (noiseObserver != null && draws != observedDraws) {
            observedDraws = draws;
            noiseObserver.accept(lastDitherNoise);
        }
    }

    public static void recordDraw() {
        DRAWS.incrementAndGet();
        FIRST_DRAW.compareAndSet(0, ManagementFactory.getRuntimeMXBean().getUptime());
    }

    public static long draws() {
        return DRAWS.get();
    }

    public static long firstDrawMillis() {
        return FIRST_DRAW.get();
    }
}
