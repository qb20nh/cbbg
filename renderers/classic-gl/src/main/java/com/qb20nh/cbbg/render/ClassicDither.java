package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;

public final class ClassicDither {
  private static BooleanSupplier backendFailure = () -> false;

  public static void setBackendFailure(BooleanSupplier failure) {
    backendFailure = failure;
  }

  public static boolean hasFailed() {
    return shaderLoadFailed || ClassicNoise.hasFailed() || backendFailure.getAsBoolean();
  }

  private static ShaderInstance shader;
  private static boolean shaderLoadFailed;
  private static NativeImage[] images;
  private static int[] textures;
  private static int nextFrame;
  private static int lastFrame;
  private static long presentations;

  private ClassicDither() {}

  public static void setShader(ShaderInstance loaded) {
    shader = loaded;
    shaderLoadFailed = false;
  }

  public static void onShaderLoadFailure(IOException failure) {
    if (shaderLoadFailed) return;
    shader = null;
    shaderLoadFailed = true;
    Cbbg.LOGGER.warn(
        "Could not load the classic CBBG dither shader; using vanilla presentation", failure);
  }

  public static void tick() {
    ClassicNoise.tick(ClassicDither::upload);
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
        && !ClassicNoise.hasFailed();
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
    closeFrames();
    shader = null; // Minecraft owns and closes registered shader programs.
  }
}
