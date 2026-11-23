package com.qb20nh.cbbg.render;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/** CPU work is serialized; public methods and GPU uploads belong to the render thread. */
public final class Gl2NoisePreparation implements AutoCloseable {
    private final Path directory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "cbbg-legacy-noise");
        thread.setDaemon(true);
        return thread;
    });
    private Future<BufferedImage[]> pending;
    private Gl2NoiseTextures textures;
    private int requestedSize;
    private boolean failed;

    public Gl2NoisePreparation(Path directory) {
        this.directory = directory;
    }

    public void request(int size, int depth, long seed) {
        request(size, depth, seed, false);
    }

    public void request(int size, int depth, long seed, boolean regenerate) {
        if (pending != null) pending.cancel(true);
        requestedSize = size;
        failed = false;
        pending = worker.submit(() -> regenerate ? Gl2NoiseImages.generate(directory, size, depth, seed)
                : Gl2NoiseImages.loadOrGenerate(directory, size, depth, seed));
    }

    public void tick() {
        tick(true);
    }

    /** A disabled renderer may finish an explicit cache regeneration without uploading textures. */
    public boolean tick(boolean upload) {
        if (pending == null || !pending.isDone()) return false;
        try {
            BufferedImage[] images = pending.get();
            if (upload) {
                Gl2NoiseTextures loaded = Gl2NoiseTextures.upload(images, requestedSize);
                if (textures != null) textures.close();
                textures = loaded;
            }
            return true;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            failed = true;
            Logger.getLogger("cbbg").log(Level.WARNING, "Legacy STBN preparation failed", failure);
            return false;
        } finally {
            pending = null;
        }
    }

    public boolean isReady() { return textures != null && pending == null && !failed; }
    public boolean hasFailed() { return failed; }
    public Gl2NoiseTextures textures() { return textures; }

    public void release() {
        if (pending != null) pending.cancel(true);
        pending = null;
        if (textures != null) textures.close();
        textures = null;
        failed = false;
    }

    @Override
    public void close() {
        release();
        worker.shutdownNow();
    }
}
