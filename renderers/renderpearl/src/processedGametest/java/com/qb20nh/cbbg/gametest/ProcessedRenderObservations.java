package com.qb20nh.cbbg.gametest;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Observes Minecraft's actual CBBG draw calls without depending on mod internals. */
public final class ProcessedRenderObservations {
    private static final Set<CompiledRenderPipeline> PIPELINES = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private static final AtomicLong DRAWS = new AtomicLong();
    private static final AtomicLong FIRST_DRAW = new AtomicLong();

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
