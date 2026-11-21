package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.qb20nh.cbbg.api.DitherOptions;
import java.util.IdentityHashMap;
import java.util.Objects;
import net.caffeinemc.mods.sodium.client.compatibility.workarounds.Workarounds;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/** OpenGL operations for the shared noise and presentation lifecycle. */
final class DitherPresentation {
  // Sodium replaces the shader blit with a framebuffer copy, including alpha.
  private static final boolean SODIUM_BLIT = FabricLoader.getInstance().isModLoaded("sodium");
  private static int texture;
  private static final IdentityHashMap<NativeImage, Integer> textures = new IdentityHashMap<>();
  private static boolean streaming;
  private static final boolean CACHE_SCREEN_QUAD =
      !FabricLoader.getInstance().isModLoaded("immediatelyfast");
  private static @Nullable VertexBuffer screenQuad;
  private static @Nullable ShaderInstance checkedShader;
  private static @Nullable ShaderInstance checkedBlit;
  private static boolean screenPassSupported;

  private DitherPresentation() {}

  @SuppressWarnings("ReferenceEquality") // Shader identity changes on resource reload.
  static boolean supportsScreenPass(boolean demo) {
    if (SODIUM_BLIT && !nativeSodiumBlit()) return false;
    var mc = Minecraft.getInstance();
    ShaderInstance shader = CbbgShaders.get(demo);
    ShaderInstance blit = mc.gameRenderer.blitShader;
    if (shader == null || blit == null) return false;
    // skipcq: JAVA-E1053
    if (shader != checkedShader || blit != checkedBlit) {
      checkedShader = shader;
      checkedBlit = blit;
      screenPassSupported = usesBuiltInShaders(mc.getResourceManager(), shader, blit);
    }
    return screenPassSupported;
  }

  private static boolean usesBuiltInShaders(
      ResourceProvider resources, ShaderInstance shader, ShaderInstance blit) {
    return shader.getClass() == ShaderInstance.class
        && blit.getClass() == ShaderInstance.class
        && blit.getName().equals("blit_screen")
        && builtIn(resources, "shaders/core/blit_screen.json", "vanilla")
        && builtIn(resources, "shaders/core/blit_screen.vsh", "vanilla")
        && builtIn(resources, "shaders/core/blit_screen.fsh", "vanilla")
        && builtIn(resources, "shaders/core/" + shader.getName() + ".json", "cbbg_lib")
        && builtIn(resources, "shaders/core/" + shader.getName() + ".fsh", "cbbg_lib")
        && builtIn(resources, "shaders/include/cbbg_dither.glsl", "cbbg_lib");
  }

  private static boolean builtIn(ResourceProvider resources, String path, String pack) {
    return resources
        .getResource(ResourceLocation.withDefaultNamespace(path))
        .map(resource -> resource.sourcePackId().equals(pack))
        .orElse(false);
  }

  private static boolean nativeSodiumBlit() {
    var loader = FabricLoader.getInstance();
    if (loader.isModLoaded("iris") || loader.isModLoaded("immediatelyfast")) return false;
    try {
      return !Workarounds.isWorkaroundEnabled(
          Workarounds.Reference.INTEL_FRAMEBUFFER_BLIT_CRASH_WHEN_UNFOCUSED);
    } catch (LinkageError unavailable) {
      return false;
    }
  }

  static void present(RenderTarget input, DitherOptions options) {
    RenderSystem.assertOnRenderThread();
    if (!SODIUM_BLIT) {
      drawScreen(input, options);
      return;
    }
    try (@SuppressWarnings("PMD.UnusedLocalVariable")
        DitherPass.GlState state = new DitherPass.GlState()) {
      drawScreen(input, options);
    } finally {
      GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
    }
  }

  private static void drawScreen(RenderTarget input, DitherOptions options) {
    ShaderInstance shader = Objects.requireNonNull(CbbgShaders.get(options.demo()));
    int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    GlStateManager._activeTexture(GL13.GL_TEXTURE1);
    int previousNoise = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    GlStateManager._activeTexture(active);
    try {
      // Match the final blit's raster state and draw into its existing framebuffer.
      GlStateManager._colorMask(true, true, true, SODIUM_BLIT);
      GlStateManager._disableDepthTest();
      GlStateManager._depthMask(false);
      GlStateManager._viewport(0, 0, input.width, input.height);
      GlStateManager._disableBlend();
      shader.setSampler("InSampler", input.getColorTextureId());
      // skipcq: JAVA-W1060
      shader.setSampler("NoiseSampler", texture);
      shader.safeGetUniform("Strength").set(options.strength());
      shader.safeGetUniform("CoordScale").set(options.scaleX(), options.scaleY());
      shader.apply();
      BufferUploader.invalidate();
      drawScreenQuad();
    } finally {
      try {
        shader.clear();
      } finally {
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._activeTexture(GL13.GL_TEXTURE1);
        GlStateManager._bindTexture(previousNoise);
        GlStateManager._activeTexture(active);
      }
    }
  }

  private static void drawScreenQuad() {
    if (!CACHE_SCREEN_QUAD) {
      BufferUploader.draw(screenQuadVertices());
      return;
    }
    // skipcq: JAVA-W1060
    VertexBuffer buffer = screenQuad;
    if (buffer == null) {
      buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
      boolean uploaded = false;
      try {
        buffer.bind();
        buffer.upload(screenQuadVertices());
        screenQuad = buffer;
        uploaded = true;
      } finally {
        if (!uploaded) buffer.close();
      }
    } else {
      buffer.bind();
    }
    buffer.draw();
  }

  private static MeshData screenQuadVertices() {
    var vertices =
        RenderSystem.renderThreadTesselator()
            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
    vertices.addVertex(0, 0, 0);
    vertices.addVertex(1, 0, 0);
    vertices.addVertex(1, 1, 0);
    vertices.addVertex(0, 1, 0);
    return vertices.buildOrThrow();
  }

  static boolean hasOverlay() {
    return Minecraft.getInstance().getOverlay() != null;
  }

  static void allocate(int size) {
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      texture = createTexture(size, size);
      if (texture <= 0) throw new IllegalStateException("Could not allocate noise texture");
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }

  static boolean isReady() {
    return texture > 0;
  }

  static int texture() {
    return texture;
  }

  static void upload(NativeImage image) {
    Integer cached = textures.get(image);
    if (cached != null) {
      texture = cached;
      return;
    }
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int created = 0;
    try {
      int destination = texture;
      // skipcq: JAVA-W1060
      if (!streaming && !textures.isEmpty()) {
        created = createTexture(image.getWidth(), image.getHeight());
        if (created > 0) {
          destination = created;
        } else {
          for (int cachedTexture : textures.values()) {
            if (cachedTexture != texture) TextureUtil.releaseTextureId(cachedTexture);
          }
          textures.clear();
          streaming = true;
        }
      }
      GlStateManager._bindTexture(destination);
      // NativeImage owns the CPU pixels; upload must not close a controller-owned frame.
      image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), false, false, false, false);
      texture = destination;
      if (!streaming) textures.put(image, destination);
      created = 0;
    } finally {
      if (created > 0) TextureUtil.releaseTextureId(created);
      GlStateManager._bindTexture(previous);
    }
  }

  private static int createTexture(int width, int height) {
    int result = TextureUtil.generateTextureId();
    if (result <= 0) return 0;
    boolean retained = false;
    try {
      TextureUtil.prepareImage(result, width, height);
      GlStateManager._bindTexture(result);
      if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != width
          || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) != height)
        return 0;
      GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      retained = true;
      return result;
    } finally {
      if (!retained) TextureUtil.releaseTextureId(result);
    }
  }

  static boolean hasDetectedNoFloatFormats() {
    return MainTargetFormatSupport.hasDetectedNoFloatFormats();
  }

  static void close() {
    CbbgDither.closeGpu();
    // skipcq: JAVA-E1053
    if (screenQuad != null) {
      screenQuad.close();
      screenQuad = null;
    }
    if (textures.isEmpty()) {
      if (texture > 0) TextureUtil.releaseTextureId(texture);
    } else {
      for (int cached : textures.values()) TextureUtil.releaseTextureId(cached);
    }
    textures.clear();
    texture = 0;
    streaming = false;
    checkedShader = null;
    checkedBlit = null;
    screenPassSupported = false;
  }
}
