package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.mixin.GpuSurfaceAccessor;
import com.qb20nh.cbbg.render.stbn.StbnTextureManager;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

@NullMarked
public final class CbbgDither {

  private static final String S_IN = "InSampler";
  private static final String S_NOISE = "NoiseSampler";
  private static final String U_DITHER_INFO = "CbbgDitherInfo";
  private static final int DITHER_INFO_UBO_SIZE = 16;

  private static final @NonNull Identifier SCREENQUAD_VERTEX =
      Identifier.withDefaultNamespace("core/screenquad");

  private static final @NonNull Identifier DITHER_SHADER =
      Identifier.fromNamespaceAndPath(Cbbg.MOD_ID, "core/cbbg_dither");
  private static final @NonNull Identifier DEMO_SHADER =
      Identifier.fromNamespaceAndPath(Cbbg.MOD_ID, "core/cbbg_demo");

  private static final @NonNull RenderPipeline DITHER_PIPELINE = DitherPipelines.ENABLED;
  private static final @NonNull RenderPipeline DEMO_PIPELINE = DitherPipelines.DEMO;

  private static final StbnTextureManager stbnManager = new StbnTextureManager();
  private static @Nullable MappableRingBuffer ditherInfoUbo;
  private static boolean presentationFailed;
  private static @Nullable GpuSurface vulkanSurface;

  private static @Nullable TextureTarget ditherTarget;

  private CbbgDither() {}

  public static void resetAfterToggle() {
    presentationFailed = false;
    DitherController.resetAfterToggle();
  }

  public static void close() {
    DitherController.close();
  }

  static void closeGpu() {
    VulkanWindowPresentation.closeUniform();
    if (ditherInfoUbo != null) {
      ditherInfoUbo.close();
      ditherInfoUbo = null;
    }
    if (ditherTarget != null) {
      ditherTarget.destroyBuffers();
      ditherTarget = null;
    }
    stbnManager.close();
  }

  public static boolean canPresentToVulkanWindow(GpuSurface surface, GpuTextureView input) {
    return surface.isAcquired()
        && surface instanceof GpuSurfaceAccessor access
        && access.cbbg$backend() instanceof VulkanWindowPresentation.Surface window
        && window.cbbg$canDraw(input);
  }

  public static void setVulkanSurface(@Nullable GpuSurface surface) {
    vulkanSurface = surface;
  }

  @SuppressWarnings("ReferenceEquality") // Only the main surface selected by MinecraftMixin.
  public static boolean presentToVulkanWindow(
      GpuSurface surface, CommandEncoder encoder, GpuTextureView input) {
    if (surface != vulkanSurface || presentationFailed || !canPresentToVulkanWindow(surface, input))
      return false;
    var window = (VulkanWindowPresentation.Surface) ((GpuSurfaceAccessor) surface).cbbg$backend();
    return Boolean.TRUE.equals(
        DitherController.renderFrame(
            true,
            () -> {
              double scale = RenderScaleCompat.getDitherScale();
              window.cbbg$draw(
                  encoder,
                  input,
                  Objects.requireNonNull(stbnManager.getView()),
                  getEffectiveStrength(),
                  DitherScale.forDimension(scale, input.getWidth(0)),
                  DitherScale.forDimension(scale, input.getHeight(0)),
                  CbbgClient.isDemoMode());
              return true;
            }));
  }

  public static boolean isDisabled() {
    return presentationFailed || DitherController.isDisabled();
  }

  public static int getStbnFrames() {
    return DitherController.getStbnFrames();
  }

  public static int getCurrentStbnFrameIndex() {
    return DitherController.getCurrentStbnFrameIndex();
  }

  /**
   * Renders the dithering pass into an RGBA8 {@link TextureTarget} and returns it.
   *
   * <p>Advances the noise frame for final presentation.
   */
  public static @Nullable TextureTarget renderDitheredTarget(GpuTextureView input) {
    return renderToTarget(input, DITHER_PIPELINE, DITHER_SHADER, "cbbg dither", true);
  }

  public static @Nullable TextureTarget renderDemoTarget(GpuTextureView input) {
    return renderToTarget(input, DEMO_PIPELINE, DEMO_SHADER, "cbbg demo", true);
  }

  public static @Nullable TextureTarget renderScreenshotTarget(GpuTextureView input) {
    return CbbgClient.isDemoMode()
        ? renderToTarget(input, DEMO_PIPELINE, DEMO_SHADER, "cbbg demo screenshot", false)
        : renderToTarget(input, DITHER_PIPELINE, DITHER_SHADER, "cbbg screenshot", false);
  }

  private static @Nullable TextureTarget renderToTarget(
      GpuTextureView input,
      @NonNull RenderPipeline pipeline,
      @NonNull Identifier fragmentShader,
      String passLabel,
      boolean advanceFrame) {
    RenderSystem.assertOnRenderThread();
    try {
      if (presentationFailed
          || !areShadersReady(fragmentShader)
          || input.getWidth(0) <= 0
          || input.getHeight(0) <= 0) {
        return null;
      }
      return DitherController.render(advanceFrame, () -> drawToTarget(input, pipeline, passLabel));
    } catch (RuntimeException failure) {
      disableWithLog(failure);
      return null;
    }
  }

  private static TextureTarget drawToTarget(
      GpuTextureView input, RenderPipeline pipeline, String passLabel) {
    if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
      throw new IllegalStateException("CBBG dither shader could not be compiled");
    }

    ensureGpuTargets(input.getWidth(0), input.getHeight(0));
    TextureTarget target = Objects.requireNonNull(ditherTarget);
    GpuTextureView ditherView = Objects.requireNonNull(target.getColorTextureView());
    CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
    GpuBuffer ditherInfo = ensureDitherInfoUbo(input);

    try (RenderPass pass =
        encoder.createRenderPass(
            () -> passLabel, ditherView, Objects.requireNonNull(Optional.empty()))) {
      drawPass(pass, input, pipeline, ditherInfo);
    }
    if (ditherInfoUbo != null) {
      ditherInfoUbo.rotate();
    }

    return target;
  }

  private static void drawPass(
      RenderPass pass, GpuTextureView input, RenderPipeline pipeline, GpuBuffer info) {
    pass.setPipeline(pipeline);
    RenderSystem.bindDefaultUniforms(pass);
    pass.setUniform(U_DITHER_INFO, info);
    pass.bindTexture(
        S_IN, input, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    pass.bindTexture(
        S_NOISE,
        stbnManager.getView(),
        RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
    pass.draw(3, 1, 0, 0);
  }

  /**
   * Presents the dithered main target when the effect is ready.
   *
   * @return true if cbbg performed the blit/present itself (caller should cancel vanilla),
   *     otherwise false to fall back to vanilla.
   */
  public static boolean blitToWindow(
      GpuTextureView input,
      CbbgConfig.Mode mode,
      GlCommandEncoder encoder,
      GlDevice device,
      int width,
      int height) {
    if (!mode.isActive()
        || CbbgClient.getEffectiveMode() != mode
        || presentationFailed
        || width <= 0
        || height <= 0
        || input.getWidth(0) != width
        || input.getHeight(0) != height
        || !WindowFramebuffer.supportsDirectDrawing()) {
      return false;
    }
    RenderPipeline pipeline = mode == CbbgConfig.Mode.DEMO ? DEMO_PIPELINE : DITHER_PIPELINE;
    Identifier shader = mode == CbbgConfig.Mode.DEMO ? DEMO_SHADER : DITHER_SHADER;
    try {
      if (!areShadersReady(shader)) {
        return false;
      }
      if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
        throw new IllegalStateException("CBBG dither shader could not be compiled");
      }
      return Boolean.TRUE.equals(
          DitherController.renderFrame(
              true,
              () -> {
                drawToWindow(input, pipeline, encoder, device, width, height);
                return true;
              }));
    } catch (RuntimeException failure) {
      disableWithLog(failure);
      return false;
    }
  }

  private static void drawToWindow(
      GpuTextureView input,
      RenderPipeline pipeline,
      GlCommandEncoder encoder,
      GlDevice device,
      int width,
      int height) {
    GpuBuffer info = ensureDitherInfoUbo(input);
    int previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    try {
      ((WindowPassEncoder) encoder).cbbg$windowPass(true);
      GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
      GlStateManager._viewport(0, 0, width, height);
      // The validated window attachment has no managed texture view.
      try (RenderPass pass =
          new RenderPass(
              new GlRenderPass(encoder, device, false, 1, new ScissorState()),
              device,
              Collections.singletonList(null),
              () -> {},
              new RenderPass.RenderArea(0, 0, width, height))) {
        pass.pushDebugGroup(() -> "cbbg window dither");
        try {
          drawPass(pass, input, pipeline, info);
        } finally {
          pass.popDebugGroup();
        }
      }
    } finally {
      ((WindowPassEncoder) encoder).cbbg$windowPass(false);
      GlStateManager._depthMask(true);
      GlStateManager._colorMask(15);
      GlStateManager._disableScissorTest();
      GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous);
    }
    if (ditherInfoUbo != null) {
      ditherInfoUbo.rotate();
    }
  }

  public static boolean blitToScreenWithDither(GpuTextureView input) {
    CbbgConfig.Mode mode = CbbgClient.getEffectiveMode();
    // Strict: do not delegate to other blit methods based on a fresh mode read.
    // Mode may change concurrently (UI thread) while we are presenting; returning
    // false falls back to
    // vanilla for this frame and avoids any mutual recursion.
    if (mode != CbbgConfig.Mode.ENABLED) {
      return false;
    }

    try {
      RenderSystem.assertOnRenderThread();

      TextureTarget out = renderDitheredTarget(input);
      if (out == null || out.getColorTextureView() == null) {
        return false;
      }

      GpuTextureView outView = out.getColorTextureView();
      if (outView == null) {
        return false;
      }
      Minecraft.getInstance()
          .windowSurface()
          .blitFromTexture(RenderSystem.getDevice().createCommandEncoder(), outView);
      return true;
    } catch (Exception e) {
      disableWithLog(e);
      return false;
    }
  }

  public static boolean blitToScreenWithDemo(GpuTextureView input) {
    CbbgConfig.Mode mode = CbbgClient.getEffectiveMode();
    // Strict: see note in blitToScreenWithDither().
    if (mode != CbbgConfig.Mode.DEMO) {
      return false;
    }

    try {
      RenderSystem.assertOnRenderThread();
      TextureTarget out = renderDemoTarget(input);
      if (out == null || out.getColorTextureView() == null) {
        return false;
      }
      GpuTextureView outView = out.getColorTextureView();
      if (outView == null) {
        return false;
      }
      Minecraft.getInstance()
          .windowSurface()
          .blitFromTexture(RenderSystem.getDevice().createCommandEncoder(), outView);
      return true;
    } catch (Exception e) {
      disableWithLog(e);
      return false;
    }
  }

  private static void ensureGpuTargets(int width, int height) {
    if (ditherTarget == null || ditherTarget.width != width || ditherTarget.height != height) {
      if (ditherTarget == null) {
        ditherTarget =
            new TextureTarget("cbbg / Dither Output", width, height, false, GpuFormat.RGBA8_UNORM);
      } else {
        ditherTarget.resize(width, height);
      }
    }
  }

  public static void reloadStbn(boolean force) {
    DitherController.reloadStbn(force);
  }

  static void allocateNoise(int size) {
    stbnManager.ensureTexture(size, size);
  }

  static boolean isNoiseReady() {
    return stbnManager.isReady();
  }

  static void uploadNoise(NativeImage frame) {
    stbnManager.upload(frame);
  }

  private static boolean areShadersReady(@NonNull Identifier fragmentShader) {
    Minecraft mc = Minecraft.getInstance();
    ShaderManager shaderManager = mc.getShaderManager();

    // Only query existence; compiling happens later when we actually use the
    // pipeline.
    if (shaderManager.getShader(SCREENQUAD_VERTEX, ShaderType.VERTEX) == null) {
      return false;
    }
    return shaderManager.getShader(fragmentShader, ShaderType.FRAGMENT) != null;
  }

  private static @NonNull GpuBuffer ensureDitherInfoUbo(GpuTextureView input) {
    if (ditherInfoUbo == null) {
      ditherInfoUbo =
          new MappableRingBuffer(
              () -> "cbbg / DitherInfo",
              GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
              DITHER_INFO_UBO_SIZE);
    }

    GpuBuffer buffer = ditherInfoUbo.currentBuffer();
    float strength = getEffectiveStrength();
    double coordScale = RenderScaleCompat.getDitherScale();
    try (GpuBufferSlice.MappedView view = buffer.map(false, true)) {
      Std140Builder.intoBuffer(view.data())
          .putFloat(strength)
          .putVec2(
              DitherScale.forDimension(coordScale, input.getWidth(0)),
              DitherScale.forDimension(coordScale, input.getHeight(0)));
    }
    return buffer;
  }

  private static float getEffectiveStrength() {
    float base = CbbgConfig.get().strength();

    // The pause/menu background blur produces very smooth gradients, which makes 8-bit output
    // banding much more obvious. Increasing dither strength only for menu-style screens keeps
    // gameplay noise unchanged while improving the blurred background.
    //
    // --- ImmediatelyFast compat: do not remove ---
    // This is purely a shader uniform tweak; it does not allocate textures or touch framebuffer
    // bindings, and should not interfere with ImmediatelyFast's render optimizations.
    Minecraft mc = Minecraft.getInstance();
    Screen screen = mc.gui.screen();
    if (screen == null) {
      return base;
    }
    if (screen.isInGameUi()) {
      return base;
    }

    int blur = mc.options.getMenuBackgroundBlurriness();
    if (blur < 1) {
      return base;
    }

    // Scale with the configured blur radius: default blur (5) becomes ~2x strength.
    float multiplier = 1.0f + (blur / 5.0f);
    float boosted = base * multiplier;
    return Math.clamp(boosted, 0.5f, 4.0f);
  }

  private static void disableWithLog(Exception e) {
    if (!presentationFailed) {
      Cbbg.LOGGER.error("Disabling cbbg due to rendering error", e);
      presentationFailed = true;
    }
  }
}
