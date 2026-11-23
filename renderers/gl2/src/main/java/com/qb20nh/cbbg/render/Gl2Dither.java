package com.qb20nh.cbbg.render;

import com.qb20nh.cbbg.config.CbbgConfig;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

public final class Gl2Dither {
    private final Path cache;
    private final IntConsumer activeTexture;
    private final IntConsumer bindTexture;
    private final Consumer<Boolean> generation;
    private Gl2NoisePreparation preparation;
    private boolean active;
    private Gl2DitherProgram program;
    private boolean programFailed;
    private int nextFrame;
    private long presentations;
    private boolean notificationPending;
    private boolean suppressed;

    public Gl2Dither(Path cache, IntConsumer activeTexture, IntConsumer bindTexture, Consumer<Boolean> generation) {
        this.cache = cache;
        this.activeTexture = activeTexture;
        this.bindTexture = bindTexture;
        this.generation = generation;
    }

    public void tick() {
        if (CbbgConfig.get().mode() == CbbgConfig.Mode.DISABLED) {
            if (preparation != null && active) {
                preparation.release();
                notificationPending = false;
            }
            active = false;
            closeProgram();
            if (notificationPending) finishPreparation(false);
            return;
        }
        if (!active) {
            if (notificationPending) active = true;
            else reload();
        }
        finishPreparation(true);
    }

    private void finishPreparation(boolean upload) {
        boolean completed = preparation.tick(upload);
        if (notificationPending && (completed || preparation.hasFailed())) {
            notificationPending = false;
            if (completed) generation.accept(true);
        }
    }

    /** Noise settings are captured on explicit reload, not while settings are being edited. */
    public void reload() {
        request(false);
    }

    public void regenerate() {
        request(true);
    }

    private void request(boolean regenerate) {
        if (preparation == null) {
            preparation = new Gl2NoisePreparation(cache);
        }
        CbbgConfig config = CbbgConfig.get();
        active = config.mode() != CbbgConfig.Mode.DISABLED;
        if (!active) preparation.release();
        preparation.request(config.stbnSize(), config.stbnDepth(), config.stbnSeed(), regenerate);
        programFailed = false;
        notificationPending = regenerate;
        if (regenerate) generation.accept(false);
    }

    public void setSuppressed(boolean value) {
        if (value && !suppressed) closeProgram();
        suppressed = value;
    }

    public void present(int width, Runnable draw) {
        if (suppressed || CbbgConfig.get().mode() == CbbgConfig.Mode.DISABLED || !isReady() || programFailed) {
            draw.run();
            return;
        }
        if (program == null) {
            try {
                program = new Gl2DitherProgram();
            } catch (RuntimeException failure) {
                programFailed = true;
                Logger.getLogger("cbbg").log(Level.WARNING, "Legacy dither shader unavailable; using vanilla presentation", failure);
                draw.run();
                return;
            }
        }
        Gl2NoiseTextures noise = textures();
        int frame = nextFrame % noise.depth();
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        activeTexture.accept(GL13.GL_TEXTURE0);
        int previousSource = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        activeTexture.accept(GL13.GL_TEXTURE1);
        int previousNoise = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            bindTexture.accept(noise.texture(frame));
            activeTexture.accept(GL13.GL_TEXTURE0);
            CbbgConfig config = CbbgConfig.get();
            program.bind(config.strength(), noise.size(), noise.size(), 1f, width,
                    config.mode() == CbbgConfig.Mode.DEMO);
            draw.run();
            presentations++;
            nextFrame = (frame + 1) % noise.depth();
        } finally {
            GL20.glUseProgram(previousProgram);
            activeTexture.accept(GL13.GL_TEXTURE1);
            bindTexture.accept(previousNoise);
            activeTexture.accept(GL13.GL_TEXTURE0);
            bindTexture.accept(previousSource);
            activeTexture.accept(previousUnit);
        }
    }

    public boolean isReady() { return preparation != null && preparation.isReady(); }
    public boolean hasFailed() { return programFailed || preparation != null && preparation.hasFailed(); }
    public Gl2NoiseTextures textures() { return preparation == null ? null : preparation.textures(); }
    public long presentations() { return presentations; }

    /** Recreate the program on the next draw without applying unconfirmed noise settings. */
    public void resourcesReloaded() {
        closeProgram();
        programFailed = false;
    }

    private void closeProgram() {
        if (program != null) program.close();
        program = null;
        nextFrame = 0;
    }

    public void close() {
        if (preparation != null) preparation.close();
        preparation = null;
        active = false;
        notificationPending = false;
        closeProgram();
        programFailed = false;
    }
}
