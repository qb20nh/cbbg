package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.qb20nh.cbbg.CbbgClient;
import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

public final class CbbgDither {

  private static final int STBN_SIZE = 128;
  private static final int STBN_FRAMES = 64;

  private static final Identifier SCREENQUAD_VERTEX = Identifier.withDefaultNamespace("core/screenquad");
  private static final Identifier DITHER_SHADER =
      Identifier.fromNamespaceAndPath(CbbgClient.MOD_ID, "core/cbbg_dither");

  private static final RenderPipeline DITHER_PIPELINE =
      RenderPipeline.builder()
          .withLocation(Identifier.fromNamespaceAndPath(CbbgClient.MOD_ID, "pipeline/cbbg_dither"))
          .withVertexShader(SCREENQUAD_VERTEX)
          .withFragmentShader(DITHER_SHADER)
          .withSampler("InSampler")
          .withSampler("NoiseSampler")
          .withDepthWrite(false)
          .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
          .withoutBlend()
          .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
          .build();

  private static final AtomicBoolean loggedFailure = new AtomicBoolean(false);
  private static volatile boolean disabled = false;

  private static NativeImage[] stbnFrames;
  private static GpuTexture stbnTexture;
  private static GpuTextureView stbnTextureView;
  private static int stbnFrameIndex = 0;

  private static TextureTarget ditherTarget;

  private CbbgDither() {}

  public static void resetAfterToggle() {
    disabled = false;
    loggedFailure.set(false);
  }

  /**
   * Renders the dithering pass into an RGBA8 {@link TextureTarget} and returns it.
   *
   * <p>This is used both for final presentation and for screenshots, so screenshots match the
   * dithered on-screen output.
   */
  public static TextureTarget renderDitheredTarget(GpuTextureView input) {
    if (disabled) {
      return null;
    }

    try {
      RenderSystem.assertOnRenderThread();

      if (!CbbgClient.isEnabled()) {
        return null;
      }

      if (!areShadersReady()) {
        return null;
      }

      final int width = input.getWidth(0);
      final int height = input.getHeight(0);
      if (width <= 0 || height <= 0) {
        return null;
      }

      ensureStbnLoaded();
      ensureGpuTargets(width, height);

      if (ditherTarget == null || ditherTarget.getColorTextureView() == null) {
        return null;
      }
      if (stbnTextureView == null) {
        return null;
      }

      CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
      uploadStbnFrame(encoder);

      try (RenderPass pass =
          encoder.createRenderPass(
              () -> "cbbg dither", ditherTarget.getColorTextureView(), OptionalInt.empty())) {
        pass.setPipeline(DITHER_PIPELINE);
        RenderSystem.bindDefaultUniforms(pass);
        pass.bindTexture(
            "InSampler",
            input,
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        pass.bindTexture(
            "NoiseSampler",
            stbnTextureView,
            RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
        pass.draw(0, 3);
      }

      return ditherTarget;
    } catch (Throwable t) {
      disableWithLog(t);
      return null;
    }
  }

  /**
   * @return true if cbbg performed the blit/present itself (caller should cancel vanilla),
   *     otherwise false to fall back to vanilla.
   */
  public static boolean blitToScreenWithDither(GpuTextureView input) {
    try {
      RenderSystem.assertOnRenderThread();

      // Iris shaderpacks must take full control of the pipeline.
      if (!CbbgClient.isEnabled()) {
        return false;
      }

      TextureTarget out = renderDitheredTarget(input);
      if (out == null || out.getColorTextureView() == null) {
        return false;
      }

      RenderSystem.getDevice().createCommandEncoder().presentTexture(out.getColorTextureView());
      return true;
    } catch (Throwable t) {
      disableWithLog(t);
      return false;
    }
  }

  private static void ensureGpuTargets(int width, int height) {
    if (ditherTarget == null || ditherTarget.width != width || ditherTarget.height != height) {
      if (ditherTarget == null) {
        ditherTarget = new TextureTarget("cbbg / Dither Output", width, height, false);
      } else {
        ditherTarget.resize(width, height);
      }
    }

    if (stbnTexture == null || stbnTexture.isClosed() || stbnTextureView == null || stbnTextureView.isClosed()) {
      stbnTexture =
          RenderSystem.getDevice()
              .createTexture(
                  () -> "cbbg / STBN",
                  GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                  TextureFormat.RGBA8,
                  STBN_SIZE,
                  STBN_SIZE,
                  1,
                  1);
      stbnTextureView = RenderSystem.getDevice().createTextureView(stbnTexture);
    }
  }

  private static void uploadStbnFrame(CommandEncoder encoder) {
    if (stbnFrames == null || stbnFrames.length != STBN_FRAMES) {
      return;
    }

    int idx = stbnFrameIndex++ % STBN_FRAMES;
    NativeImage frame = stbnFrames[idx];
    if (frame == null || stbnTexture == null) {
      return;
    }

    encoder.writeToTexture(stbnTexture, frame);
  }

  private static void ensureStbnLoaded() throws IOException {
    if (stbnFrames != null) {
      return;
    }

    ResourceManager rm = Minecraft.getInstance().getResourceManager();
    NativeImage[] frames = new NativeImage[STBN_FRAMES];
    for (int i = 0; i < STBN_FRAMES; i++) {
      Identifier id =
          Identifier.fromNamespaceAndPath(
              CbbgClient.MOD_ID, "textures/stbn/stbn_unitvec3_2dx1d_128x128x64_" + i + ".png");
      try (InputStream in = rm.open(id)) {
        frames[i] = NativeImage.read(in);
      }
    }
    stbnFrames = frames;
    CbbgClient.LOGGER.info("Loaded {} STBN frames for dithering", STBN_FRAMES);
  }

  private static boolean areShadersReady() {
    Minecraft mc = Minecraft.getInstance();
    ShaderManager shaderManager = mc.getShaderManager();
    if (shaderManager == null) {
      return false;
    }

    // Only query existence; compiling happens later when we actually use the pipeline.
    return shaderManager.getShader(SCREENQUAD_VERTEX, ShaderType.VERTEX) != null
        && shaderManager.getShader(DITHER_SHADER, ShaderType.FRAGMENT) != null;
  }

  private static void disableWithLog(Throwable t) {
    disabled = true;
    if (loggedFailure.compareAndSet(false, true)) {
      CbbgClient.LOGGER.warn("Disabling cbbg dithering due to an error; falling back to vanilla blit.", t);
    }
  }
}
