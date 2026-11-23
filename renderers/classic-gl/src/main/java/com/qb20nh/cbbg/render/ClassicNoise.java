package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.stbn.STBNCache;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import com.qb20nh.cbbg.render.stbn.STBNLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Owns the cache and generation lifecycle shared by the classic renderer adapters. */
public final class ClassicNoise {
  private record NoiseSpec(int size, int depth, long seed) {
    CompletableFuture<STBNGenerator.STBNFields> prepare(boolean force) {
      return STBNGenerator.generateAsync(size, size, depth, seed, force);
    }
  }

  private static NoiseSpec requested;
  private static CompletableFuture<STBNGenerator.STBNFields> pending;
  private static CompletableFuture<Void> notification;
  private static boolean failed;

  private ClassicNoise() {}

  public static void reload(boolean force) {
    RenderSystem.assertOnRenderThread();
    if (!force && pending != null && !pending.isDone()) return;
    CbbgConfig config = CbbgConfig.get();
    var spec = new NoiseSpec(config.stbnSize(), config.stbnDepth(), config.stbnSeed());
    if (force) {
      try {
        Files.deleteIfExists(
            STBNCache.CACHE_DIR.resolve(
                String.format(STBNCache.HASH_FILE_FMT, spec.size(), spec.size(), spec.depth())));
      } catch (IOException failure) {
        Cbbg.LOGGER.error("Could not invalidate STBN cache for regeneration", failure);
        return;
      }
      notification = new CompletableFuture<>();
      GenerationNotifications.started(notification);
    }
    start(spec, force);
  }

  /** The uploader takes ownership of the decoded images. */
  public static void tick(Consumer<NativeImage[]> upload) {
    // Settings edits apply on explicit reload, after the user confirms them.
    if (requested == null) reload(false);
    var request = pending;
    if (request == null || !request.isDone()) return;
    NoiseSpec spec = requested;
    pending = null;
    try {
      NativeImage[] images =
          STBNLoader.loadOrGenerate(
              spec.size(), spec.size(), spec.depth(), spec.seed(), request.join());
      if (images == null) {
        // A cache can disappear between worker validation and image decoding.
        pending = spec.prepare(true);
        return;
      }
      upload.accept(images);
      if (notification != null) notification.complete(null);
    } catch (RuntimeException failure) {
      failed = true;
      if (notification != null) notification.completeExceptionally(failure);
      Cbbg.LOGGER.error("Could not prepare classic STBN textures", failure);
    } finally {
      STBNGenerator.release(request);
      if (pending == null) notification = null;
    }
  }

  private static void start(NoiseSpec spec, boolean force) {
    requested = spec;
    failed = false;
    pending = spec.prepare(force);
  }

  public static boolean isPreparing() {
    return pending != null;
  }

  public static boolean hasFailed() {
    return failed;
  }

  public static void close() {
    GenerationNotifications.close();
    notification = null;
    if (pending != null) {
      pending.cancel(true);
      STBNGenerator.release(pending);
    }
    pending = null;
    requested = null;
    failed = false;
  }
}
