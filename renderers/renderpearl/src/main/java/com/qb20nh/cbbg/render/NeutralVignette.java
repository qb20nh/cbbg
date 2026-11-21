package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.mixin.FrontendGpuDeviceAccessor;
import com.qb20nh.cbbg.mixin.RenderSystemPipelineCacheAccessor;
import com.qb20nh.cbbg.platform.LoaderPlatform;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Omits the qualified neutral OpenGL vignette without changing its nonneutral path. */
@NullMarked
public final class NeutralVignette {
  private static final Identifier SHADER =
      Identifier.withDefaultNamespace("core/position_tex_color");
  private static final Identifier TEXTURE =
      Identifier.withDefaultNamespace("textures/misc/vignette.png");
  private static final BlendFunction BLEND =
      new BlendFunction(
          BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_COLOR, BlendFactor.ZERO, BlendFactor.ONE);
  // Raw 26.3 engine roots, independent of active packs and the classpath resource search order.
  private static final String VERTEX_SHA =
      "98df7a3e71c56e710e3957015c22501524f9cf6a8ce92bbca5f46b901e25ff6d";
  private static final String FRAGMENT_SHA =
      "598daa2518ddc018438107cd7e2eb0375b4c083f5c00152662b03c5e0ac8b7ff";
  private static final NeutralVignette CHECKED = new NeutralVignette();

  private WeakReference<Object> checkedCache = new WeakReference<>(null);
  private @Nullable RenderPipeline checkedPipeline;
  private boolean eligible;

  /** The cache owns its shader source; this helper never retains that source or its GPU objects. */
  public interface SourceHolder {
    ShaderSource cbbg$shaderSource();
  }

  NeutralVignette() {}

  // Only the original singleton pipeline is qualified; structural equality is insufficient.
  @SuppressWarnings("ReferenceEquality")
  public static boolean shouldSkip(RenderPipeline pipeline, Identifier texture, int color) {
    if (!isNeutral(color)) return false;
    if (pipeline != RenderPipelines.VIGNETTE || !TEXTURE.equals(texture)) return false;
    if (!usesQualifiedOpenGlBackend()) return false;
    var client = Minecraft.getInstance();
    var main = client.gameRenderer.mainRenderTarget();
    var colorTexture = main == null ? null : main.getColorTexture();
    if (!supportsTarget(colorTexture)) return false;
    PipelineCache cache = RenderSystemPipelineCacheAccessor.cbbg$currentPipelineCache();
    if (cache == null
        || !CHECKED.matchesGeneration(cache, pipeline, ((SourceHolder) cache).cbbg$shaderSource()))
      return false;
    return usesRgba8Vignette(client, texture);
  }

  private static boolean usesQualifiedOpenGlBackend() {
    if (Compatibility.UNQUALIFIED_COMPILER) return false;
    var device = RenderSystem.getDevice();
    if (device.getClass() != FrontendGpuDevice.class) return false;
    var backend = ((FrontendGpuDeviceAccessor) device).cbbg$backend();
    return backend != null && backend.getClass() == GlDevice.class;
  }

  private static boolean supportsTarget(@Nullable GpuTexture colorTexture) {
    return colorTexture != null
        && !colorTexture.isClosed()
        && supportsState(CbbgClient.isEnabled(), colorTexture.getFormat());
  }

  private static boolean usesRgba8Vignette(Minecraft client, Identifier texture) {
    try {
      // Texture reloads can replace a GPU texture without replacing the shader cache.
      var actual = client.getTextureManager().getTexture(texture);
      if (actual == null || actual.getClass() != SimpleTexture.class) return false;
      var gpu = actual.getTexture();
      return gpu != null && !gpu.isClosed() && gpu.getFormat() == GpuFormat.RGBA8_UNORM;
    } catch (RuntimeException | LinkageError unavailable) {
      return false;
    }
  }

  static boolean isNeutral(int color) {
    return color == 0xff000000;
  }

  static boolean supportsState(boolean enabled, GpuFormat format) {
    return enabled && format == GpuFormat.RGBA32_FLOAT;
  }

  // Called on the render thread. Failed reloads leave the old active cache in place.
  // Cache and pipeline replacement, rather than equal contents, starts a new generation.
  @SuppressWarnings("ReferenceEquality")
  boolean matchesGeneration(Object cache, RenderPipeline pipeline, ShaderSource source) {
    if (cache != checkedCache.get() || pipeline != checkedPipeline) {
      checkedCache = new WeakReference<>(cache);
      checkedPipeline = pipeline;
      eligible = false;
      try {
        eligible = descriptorMatches(pipeline) && sourceMatches(source);
      } catch (RuntimeException | LinkageError unavailable) {
        return false;
      }
    }
    return eligible;
  }

  static boolean sourceMatches(ShaderSource source) {
    // Ordinary Configs generations contain immutable maps. Other source lifecycles are unqualified.
    return source.getClass().getName().equals("net.minecraft.client.renderer.ShaderManager$Configs")
        && digestMatches(source.getShader(SHADER, ShaderType.VERTEX), VERTEX_SHA)
        && digestMatches(source.getShader(SHADER, ShaderType.FRAGMENT), FRAGMENT_SHA);
  }

  private static boolean digestMatches(@Nullable String source, String expected) {
    if (source == null) return false;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
      return expected.equals(HexFormat.of().formatHex(digest));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static boolean descriptorMatches(RenderPipeline pipeline) {
    if (!matchesShaderState(pipeline)
        || !usesExpectedVertexLayout(pipeline)
        || !matchesRasterState(pipeline)) return false;
    for (int slot = 1; slot < 16; slot++) {
      if (pipeline.getVertexFormatBinding(slot) != null) return false;
    }
    var target = pipeline.getColorTargetStates().getFirst();
    return target != null
        && target.writeMask() == 15
        && target.format() == GpuFormat.RGBA8_UNORM
        && target.blendFunction().filter(BLEND::equals).isPresent();
  }

  private static boolean matchesShaderState(RenderPipeline pipeline) {
    return pipeline.getClass() == RenderPipeline.class
        && pipeline.getShaders().size() == 2
        && SHADER.equals(pipeline.getShaders().get(ShaderType.VERTEX))
        && SHADER.equals(pipeline.getShaders().get(ShaderType.FRAGMENT))
        && pipeline.getShaderDefines().isEmpty()
        && pipeline.getDepthStencilState() == null;
  }

  private static boolean matchesRasterState(RenderPipeline pipeline) {
    return pipeline.getPrimitiveTopology() == PrimitiveTopology.QUADS
        && pipeline.getPolygonMode() == PolygonMode.FILL
        && pipeline.pushConstantSize() == 0
        && !pipeline.wantsDepthTexture()
        && pipeline.getColorTargetStates().size() == 1;
  }

  @SuppressWarnings("ReferenceEquality") // Only the vanilla singleton layout is qualified.
  private static boolean usesExpectedVertexLayout(RenderPipeline pipeline) {
    return pipeline.getVertexFormatBindings().size() == 16
        && pipeline.getVertexFormatBinding(0) == DefaultVertexFormat.POSITION_TEX_COLOR;
  }

  private static final class Compatibility {
    // These mods remain active; only this unqualified shortcut is withheld.
    private static final boolean UNQUALIFIED_COMPILER =
        LoaderPlatform.isModLoaded("iris")
            || LoaderPlatform.isModLoaded("oculus")
            || LoaderPlatform.isModLoaded("sulkan")
            || LoaderPlatform.isModLoaded("immediatelyfast");
  }
}
