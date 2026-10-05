package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.stbn.StbnTextureManager;
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

  private static final @NonNull Identifier DITHER_PIPELINE_LOCATION =
      Identifier.fromNamespaceAndPath(Cbbg.MOD_ID, "pipeline/cbbg_dither");
  private static final @NonNull Identifier DEMO_PIPELINE_LOCATION =
      Identifier.fromNamespaceAndPath(Cbbg.MOD_ID, "pipeline/cbbg_demo");

  private static final @NonNull RenderPipeline DITHER_PIPELINE =
      RenderPipeline.builder()
          .withLocation(DITHER_PIPELINE_LOCATION)
          .withVertexShader(SCREENQUAD_VERTEX)
          .withFragmentShader(DITHER_SHADER)
          .withBindGroupLayout(
              BindGroupLayout.builder()
                  .withSampler(S_IN)
                  .withSampler(S_NOISE)
                  .withUniform(U_DITHER_INFO, UniformType.UNIFORM_BUFFER)
                  .build())
          .withDepthStencilState(
              Optional.empty()) // new DepthStencilState(CompareOp.ALWAYS_PASS, false)
          .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15))
          .withVertexBinding(0, VertexFormat.builder(0).build())
          .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
          .build();

  private static final @NonNull RenderPipeline DEMO_PIPELINE =
      RenderPipeline.builder()
          .withLocation(DEMO_PIPELINE_LOCATION)
          .withVertexShader(SCREENQUAD_VERTEX)
          .withFragmentShader(DEMO_SHADER)
          .withBindGroupLayout(
              BindGroupLayout.builder()
                  .withSampler(S_IN)
                  .withSampler(S_NOISE)
                  .withUniform(U_DITHER_INFO, UniformType.UNIFORM_BUFFER)
                  .build())
          .withDepthStencilState(Optional.empty())
          .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15))
          .withVertexBinding(0, VertexFormat.builder(0).build())
          .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
          .build();

  private static final StbnTextureManager stbnManager = new StbnTextureManager();
  private static @Nullable MappableRingBuffer ditherInfoUbo;
  private static boolean presentationFailed;

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
    GpuBuffer ditherInfo = ensureDitherInfoUbo();

    try (RenderPass pass =
        encoder.createRenderPass(
            () -> passLabel, ditherView, Objects.requireNonNull(Optional.empty()))) {
      pass.setPipeline(pipeline);
      RenderSystem.bindDefaultUniforms(pass);
      pass.setUniform(U_DITHER_INFO, ditherInfo);

      pass.bindTexture(
          S_IN, input, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
      pass.bindTexture(
          S_NOISE,
          stbnManager.getView(),
          RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
      pass.draw(3, 1, 0, 0);
    }
    if (ditherInfoUbo != null) {
      ditherInfoUbo.rotate();
    }

    return target;
  }

  /**
   * Presents the dithered main target when the effect is ready.
   *
   * @return true if cbbg performed the blit/present itself (caller should cancel vanilla),
   *     otherwise false to fall back to vanilla.
   */
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
    RenderSystem.getDevice()
        .createCommandEncoder()
        .writeToTexture(Objects.requireNonNull(stbnManager.getTexture()), frame);
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

  private static @NonNull GpuBuffer ensureDitherInfoUbo() {
    if (ditherInfoUbo == null) {
      ditherInfoUbo =
          new MappableRingBuffer(
              () -> "cbbg / DitherInfo",
              GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
              DITHER_INFO_UBO_SIZE);
    }

    GpuBuffer buffer = ditherInfoUbo.currentBuffer();
    float strength = getEffectiveStrength();
    float coordScale = RenderScaleCompat.getDitherCoordScale();
    try (GpuBufferSlice.MappedView view = buffer.map(false, true)) {
      Std140Builder.intoBuffer(view.data()).putFloat(strength).putVec2(coordScale, coordScale);
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
