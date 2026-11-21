package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.stbn.STBNCache;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import com.qb20nh.cbbg.render.stbn.STBNLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;

public final class ClassicDither {
  private record NoiseSpec(int size, int depth, long seed) {}

  private static ShaderInstance shader;
  private static NoiseSpec requested;
  private static CompletableFuture<STBNGenerator.STBNFields> pending;
  private static NativeImage[] images;
  private static int[] textures;
  private static int nextFrame;
  private static int lastFrame;
  private static long presentations;
  private static boolean failed;
  private static CompletableFuture<Void> notification;

  private ClassicDither() {}

  public static void setShader(ShaderInstance loaded) {
    shader = loaded;
  }

  public static void reload(boolean force) {
    RenderSystem.assertOnRenderThread();
    if (!force && pending != null && !pending.isDone()) return;
    if (force) {
      CbbgConfig config = CbbgConfig.get();
      try {
        Files.deleteIfExists(
            STBNCache.CACHE_DIR.resolve(
                String.format(
                    STBNCache.HASH_FILE_FMT,
                    config.stbnSize(),
                    config.stbnSize(),
                    config.stbnDepth())));
      } catch (IOException failure) {
        Cbbg.LOGGER.error("Could not invalidate STBN cache for regeneration", failure);
        return;
      }
    }
    if (force) {
      notification = new CompletableFuture<>();
      GenerationNotifications.started(notification);
    }
    requested = null;
  }

  public static void tick() {
    // Settings edits take effect on the next explicit reload, not while confirming them.
    if (requested == null) {
      CbbgConfig config = CbbgConfig.get();
      requested = new NoiseSpec(config.stbnSize(), config.stbnDepth(), config.stbnSeed());
      failed = false;
      pending =
          STBNGenerator.generateAsync(
              requested.size(), requested.size(), requested.depth(), requested.seed());
    }
    NoiseSpec spec = requested;
    if (pending == null || !pending.isDone()) return;
    try {
      var request = pending;
      NativeImage[] loaded;
      try {
        loaded =
            STBNLoader.loadOrGenerate(
                spec.size(), spec.size(), spec.depth(), spec.seed(), request.join());
      } finally {
        STBNGenerator.release(request);
      }
      if (loaded == null) {
        // A cache may disappear between the worker's validation and image decoding.
        pending = STBNGenerator.generateAsync(spec.size(), spec.size(), spec.depth(), spec.seed());
        return;
      }
      upload(loaded);
      pending = null;
      if (notification != null) notification.complete(null);
    } catch (Exception failure) {
      pending = null;
      failed = true;
      if (notification != null) notification.completeExceptionally(failure);
      Cbbg.LOGGER.error("Could not prepare classic STBN textures", failure);
    }
  }

  private static void upload(NativeImage[] loaded) {
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int[] allocated = new int[loaded.length];
    try {
      for (int frame = 0; frame < loaded.length; frame++) {
        allocated[frame] = TextureUtil.generateTextureId();
        TextureUtil.prepareImage(
            allocated[frame], loaded[frame].getWidth(), loaded[frame].getHeight());
        GlStateManager._bindTexture(allocated[frame]);
        loaded[frame].upload(0, 0, 0, false);
        GlStateManager._texParameter(
            GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(
            GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      }
    } catch (RuntimeException failure) {
      for (int texture : allocated) if (texture != 0) TextureUtil.releaseTextureId(texture);
      for (NativeImage image : loaded) image.close();
      throw failure;
    } finally {
      GlStateManager._bindTexture(previous);
    }
    closeFrames();
    textures = allocated;
    images = loaded;
    nextFrame = 0;
  }

  public static boolean needsShaderPresentation(RenderTarget target) {
    return target == Minecraft.getInstance().getMainRenderTarget()
        && CbbgClient.isEnabled()
        && shader != null
        && textures != null
        && !failed;
  }

  public static ShaderInstance forPresentation(RenderTarget target, ShaderInstance vanilla) {
    if (!needsShaderPresentation(target)) return vanilla;
    CbbgConfig config = CbbgConfig.get();
    lastFrame = nextFrame;
    nextFrame = (nextFrame + 1) % textures.length;
    shader.setSampler("NoiseSampler", textures[lastFrame]);
    shader.safeGetUniform("Strength").set(config.strength());
    float coordinateScale = RenderScaleCompat.getDitherCoordScale();
    shader.safeGetUniform("CoordScale").set(coordinateScale, coordinateScale);
    shader.safeGetUniform("Demo").set(config.mode() == CbbgConfig.Mode.DEMO ? 1f : 0f);
    presentations++;
    return shader;
  }

  public static boolean isReady() {
    return requested != null && shader != null && textures != null && pending == null && !failed;
  }

  public static boolean hasFailed() {
    return failed;
  }

  static void blitForScreenshot(RenderTarget target) {
    // Capture the displayed noise frame without advancing the animation.
    int next = nextFrame;
    nextFrame = lastFrame;
    try {
      target.blitToScreen(target.width, target.height);
    } finally {
      nextFrame = next;
    }
  }

  private static void closeFrames() {
    if (textures != null) for (int texture : textures) TextureUtil.releaseTextureId(texture);
    if (images != null) for (NativeImage image : images) image.close();
    textures = null;
    images = null;
  }

  public static void close() {
    GenerationNotifications.close();
    notification = null;
    if (pending != null) pending.cancel(true);
    pending = null;
    closeFrames();
    shader = null; // Minecraft owns and closes registered shader programs.
    requested = null;
  }
}
