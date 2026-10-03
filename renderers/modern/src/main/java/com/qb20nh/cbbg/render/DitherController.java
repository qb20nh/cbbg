package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import com.qb20nh.cbbg.render.stbn.STBNLoader;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Render-thread owner of noise frames and presentation lifecycle. */
@NullMarked
public final class DitherController {
  private static @Nullable CbbgConfig previousSettings;
  private static CbbgConfig.@Nullable Mode previousMode;
  private static @Nullable NoiseKey key;
  private static @Nullable CompletableFuture<NativeImage @Nullable []> loading;
  private static NativeImage @Nullable [] frames;
  private static int nextFrame;
  private static int shownFrame;
  private static int uploadedFrame = -1;
  private static boolean failed;
  private static long presentations;

  private DitherController() {}

  public static void resetAfterToggle() {
    RenderSystem.assertOnRenderThread();
    releaseNoise();
    failed = false;
  }

  public static void reloadStbn(boolean force) {
    RenderSystem.assertOnRenderThread();
    NoiseKey requested = new NoiseKey(CbbgConfig.get());
    if (loading != null && !force && requested.equals(key)) {
      return;
    }
    resetAfterToggle();
    if (force) {
      STBNLoader.clearCacheExceptDefaults();
    }
    startLoading(requested, force);
    if (force) {
      GenerationNotifications.started(Objects.requireNonNull(loading));
    }
  }

  private static void startLoading(NoiseKey requested, boolean force) {
    key = requested;
    CompletableFuture<NativeImage @Nullable []> generation =
        STBNGenerator.generateAsync(
                requested.size, requested.size, requested.depth, requested.seed, force)
            .<NativeImage @Nullable []>thenApplyAsync(
                fields ->
                    STBNLoader.loadOrGenerate(
                        requested.size, requested.size, requested.depth, requested.seed, fields));
    loading = generation;
    GenerationNotifications.follow(generation);
  }

  public static void beginFrame() {
    CbbgConfig settings = CbbgConfig.get();
    CbbgConfig.Mode mode = CbbgClient.getEffectiveMode();
    if (previousSettings != null
        && (previousMode != mode || previousSettings.pixelFormat() != settings.pixelFormat())) {
      MainTargets.refreshFormats();
      if (failed) {
        releaseNoise();
      }
      failed = false;
    }
    previousSettings = settings;
    previousMode = mode;
    if (!mode.isActive()) {
      releaseNoise();
      return;
    }
    // Keep the active noise until Generate/reload explicitly applies edited settings.
    if (key == null) {
      releaseNoise();
      failed = false;
      startLoading(new NoiseKey(settings), false);
    }
  }

  static @Nullable TextureTarget render(boolean advance, Supplier<TextureTarget> draw) {
    if (failed || !CbbgClient.isEnabled() || DitherPresentation.hasOverlay()) {
      return null;
    }
    try {
      if (loading != null) {
        if (!loading.isDone()) {
          return null;
        }
        frames = loading.join();
        loading = null;
        if (frames == null || frames.length == 0) {
          throw new IllegalStateException("Noise generation produced no frames");
        }
        DitherPresentation.allocate(Objects.requireNonNull(key).size);
      }
      if (frames == null || !DitherPresentation.isReady()) {
        return null;
      }
      int index = advance ? nextFrame : shownFrame;
      if (uploadedFrame != index) {
        DitherPresentation.upload(frames[index]);
        uploadedFrame = index;
      }
      TextureTarget output = draw.get();
      if (advance) {
        shownFrame = index;
        nextFrame = (index + 1) % frames.length;
        presentations++;
      }
      return output;
    } catch (RuntimeException failure) {
      NoiseKey failedKey = key;
      releaseNoise();
      key = failedKey;
      failed = true;
      // Keep the requested settings; an effect failure must not rewrite user preferences.
      Cbbg.LOGGER.warn("CBBG rendering disabled after a noise or GPU failure", failure);
      return null;
    }
  }

  public static long getPresentationCount() {
    return presentations;
  }

  public static int getCurrentStbnFrameIndex() {
    return shownFrame;
  }

  public static int getStbnFrames() {
    return frames == null ? 0 : frames.length;
  }

  public static boolean isReady() {
    return frames != null && DitherPresentation.isReady() && !failed;
  }

  public static boolean isDisabled() {
    return failed;
  }

  public static boolean hasDetectedNoFloatFormats() {
    return DitherPresentation.hasDetectedNoFloatFormats();
  }

  public static void close() {
    GenerationNotifications.close();
    releaseNoise();
    previousSettings = null;
    previousMode = null;
  }

  private static void releaseNoise() {
    if (loading != null) {
      loading
          .thenAccept(DitherController::closeImages)
          .exceptionally(
              failure -> {
                if (!(failure instanceof CancellationException)
                    && !(failure.getCause() instanceof CancellationException)) {
                  Cbbg.LOGGER.error("Pending STBN load or image disposal failed", failure);
                }
                return null;
              });
      loading = null;
    }
    DitherPresentation.close();
    closeImages(frames);
    frames = null;
    key = null;
    nextFrame = 0;
    shownFrame = 0;
    uploadedFrame = -1;
  }

  private static void closeImages(NativeImage @Nullable [] images) {
    if (images != null) {
      for (NativeImage image : images) {
        image.close();
      }
    }
  }

  private record NoiseKey(int size, int depth, long seed) {
    private NoiseKey(CbbgConfig settings) {
      this(settings.stbnSize(), settings.stbnDepth(), settings.stbnSeed());
    }
  }
}
