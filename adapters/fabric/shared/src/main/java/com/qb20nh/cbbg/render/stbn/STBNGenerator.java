package com.qb20nh.cbbg.render.stbn;

import com.qb20nh.cbbg.math.BlueNoise;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class STBNGenerator {

    private STBNGenerator() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("cbbg-gen");

    // Pure data container
    public record STBNFields(double[] uField, double[] vField, long seed) {
        public STBNFields(double[] uField, double[] vField) {
            this(uField, vField, 0L);
        }
        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            STBNFields that = (STBNFields) o;
            return seed == that.seed && Arrays.equals(uField, that.uField) && Arrays.equals(vField, that.vField);
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(uField);
            result = 31 * result + Arrays.hashCode(vField);
            return result + Long.hashCode(seed);
        }

        @Override
        public String toString() {
            return "STBNFields{" + "uField=" + Arrays.toString(uField) + ", vField="
                    + Arrays.toString(vField) + '}';
        }
    }

    private static final AtomicReference<CompletableFuture<STBNFields>> pendingFuture =
            new AtomicReference<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "cbbg-stbn");
        thread.setDaemon(true);
        return thread;
    });

    public static synchronized CompletableFuture<STBNFields> generateAsync(int w, int h, int d, long seed) {
        CompletableFuture<STBNFields> prev = pendingFuture.get();
        if (prev != null && !prev.isDone()) {
            prev.cancel(true);
        }

        CompletableFuture<STBNFields> future = new CompletableFuture<>();
        FutureTask<STBNFields> task = new FutureTask<>(() -> {
            try {
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }

                if (STBNCache.isCacheValid(w, h, d, seed)) {
                    LOGGER.info("Valid STBN cache found for {}x{}x{}. Skipping math generation.", w,
                            h, d);
                    return null;
                }

                LOGGER.info("Starting Async STBN Math Generation ({}x{}x{})...", w, h, d);
                long start = System.currentTimeMillis();

                // Generate U and V fields (Spatio-Temporal Blue Noise)
                // If seed is 0, use existing constants, otherwise mix.
                long seedU = BlueNoise.stbnUSeed(seed);
                long seedV = BlueNoise.stbnVSeed(seed);

                double[] uField = BlueNoise.generateScalarField(w, h, d, seedU);
                if (Thread.currentThread().isInterrupted())
                    return null;

                double[] vField = BlueNoise.generateScalarField(w, h, d, seedV);
                if (Thread.currentThread().isInterrupted())
                    return null;

                long dt = System.currentTimeMillis() - start;
                LOGGER.info("STBN Math Complete in {} ms", dt);

                return new STBNFields(uField, vField, seed);
            } catch (Exception e) {
                // If interrupted, just return null silently
                if (e instanceof InterruptedException)
                    return null;
                throw new CompletionException(e);
            }
        }) {
            @Override
            protected void done() {
                try {
                    future.complete(get());
                } catch (CancellationException cancelled) {
                    future.cancel(false);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    future.completeExceptionally(interrupted);
                } catch (ExecutionException failed) {
                    future.completeExceptionally(failed.getCause());
                }
            }
        };
        // CompletableFuture.cancel alone does not interrupt its supplier. Link
        // cancellation to the actual task; serialize work until it cooperates.
        future.whenComplete((fields, failure) -> {
            if (future.isCancelled()) task.cancel(true);
        });

        pendingFuture.set(future);
        WORKER.execute(task);
        return future;
    }

    public static CompletableFuture<STBNFields> get() {
        return pendingFuture.get();
    }

    public static int calculatePixelColor(double u, double v) {
        return BlueNoise.calculatePixelColor(u, v);
    }
}
