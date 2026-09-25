package com.qb20nh.cbbg.gametest;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;

/** Test-only substitutions for inputs bound by the real dither render pass. */
public final class ProcessedDitherInputs {
    private static Scope current;

    private ProcessedDitherInputs() {}

    public static Scope overrideNoise(GpuTextureView noise) {
        return install(null, noise, null);
    }

    static Scope overrideAll(GpuTextureView input, GpuTextureView noise, GpuBufferSlice info) {
        return install(input, noise, info);
    }

    private static Scope install(GpuTextureView input, GpuTextureView noise, GpuBufferSlice info) {
        Scope scope = new Scope(current, input, noise, info);
        current = scope;
        return scope;
    }

    public static GpuTextureView texture(boolean dither, String name, GpuTextureView original) {
        Scope scope = current;
        if (dither && scope != null) {
            if (name.equals("InSampler") && scope.input != null) return scope.input;
            if (name.equals("NoiseSampler") && scope.noise != null) return scope.noise;
        }
        return original;
    }

    public static GpuBufferSlice uniform(boolean dither, String name, GpuBufferSlice original) {
        Scope scope = current;
        return dither && scope != null && name.equals("CbbgDitherInfo") && scope.info != null
                ? scope.info : original;
    }

    public static final class Scope implements AutoCloseable {
        private final Scope previous;
        private final GpuTextureView input;
        private final GpuTextureView noise;
        private final GpuBufferSlice info;

        private Scope(Scope previous, GpuTextureView input, GpuTextureView noise, GpuBufferSlice info) {
            this.previous = previous;
            this.input = input;
            this.noise = noise;
            this.info = info;
        }

        @Override
        public void close() {
            if (current != this) throw new IllegalStateException("Dither input scopes closed out of order");
            current = previous;
        }
    }
}
