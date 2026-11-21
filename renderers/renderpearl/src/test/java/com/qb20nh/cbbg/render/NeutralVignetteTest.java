package com.qb20nh.cbbg.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@NullMarked
class NeutralVignetteTest {
  @Test
  void keepsEveryNonneutralTintOnTheNativePath() {
    assertTrue(NeutralVignette.isNeutral(0xff000000));
    for (int color : new int[] {0, 0x7f000000, 0xff010000, 0xff000100, 0xff000001, 0xff00bfbf}) {
      assertFalse(NeutralVignette.isNeutral(color));
      assertFalse(
          NeutralVignette.shouldSkip(
              RenderPipelines.VIGNETTE,
              Identifier.withDefaultNamespace("textures/misc/vignette.png"),
              color));
    }
  }

  @Test
  void requiresEffectiveEnablementAndTheQualifiedTargetFormat() {
    assertTrue(NeutralVignette.supportsState(true, GpuFormat.RGBA32_FLOAT));
    assertFalse(NeutralVignette.supportsState(false, GpuFormat.RGBA32_FLOAT));
    assertFalse(NeutralVignette.supportsState(true, GpuFormat.RGBA16_FLOAT));
    assertFalse(NeutralVignette.supportsState(true, GpuFormat.RGBA8_UNORM));
  }

  @Test
  void acceptsTheActualDescriptorAndRejectsChangedDrawState() {
    assertTrue(NeutralVignette.descriptorMatches(RenderPipelines.VIGNETTE));
    assertTrue(NeutralVignette.descriptorMatches(vignette().build()));
    assertFalse(
        NeutralVignette.descriptorMatches(
            vignette().withVertexBinding(1, DefaultVertexFormat.POSITION_TEX_COLOR).build()));
    assertFalse(NeutralVignette.descriptorMatches(vignette().withShaderDefine("CHANGED").build()));
    assertFalse(
        NeutralVignette.descriptorMatches(
            vignette().withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build()));
    assertFalse(
        NeutralVignette.descriptorMatches(
            vignette()
                .withColorTargetState(
                    new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15))
                .build()));
    assertFalse(
        NeutralVignette.descriptorMatches(
            PipelineFormats.withColorFormat(RenderPipelines.VIGNETTE, 0, GpuFormat.RGBA32_FLOAT)));
  }

  @Test
  void pinsBothShaderRootsRatherThanTrustingTheActiveResourceReference() throws Exception {
    String vertex = shader("vsh"), fragment = shader("fsh");
    assertTrue(NeutralVignette.sourceMatches(configs(vertex, fragment)));
    assertFalse(NeutralVignette.sourceMatches(configs(vertex + "\n// replaced\n", fragment)));
    assertFalse(NeutralVignette.sourceMatches(configs(vertex, fragment + "\n// replaced\n")));
    ShaderSource decorated =
        new ShaderSource() {
          @Override
          public @Nullable String getShader(Identifier id, ShaderType type) {
            return type == ShaderType.VERTEX ? vertex : fragment;
          }

          @Override
          public @Nullable CachedIncludeSource getInclude(Identifier id) {
            return null;
          }

          @Override
          public void close() {}
        };
    assertFalse(NeutralVignette.sourceMatches(decorated));
  }

  @Test
  void rechecksPromotedGenerationsAndKeepsTheActiveGenerationAfterFailedReload() throws Exception {
    String vertex = shader("vsh"), fragment = shader("fsh");
    ShaderSource original = configs(vertex, fragment);
    ShaderSource changed = configs(vertex, fragment + "\n// pack replacement\n");
    var check = new NeutralVignette();
    var pipeline = RenderPipelines.VIGNETTE;
    Object active = new Object();
    assertTrue(check.matchesGeneration(active, pipeline, original));
    // A failed pending reload is never promoted; the original cache/source stay active.
    assertTrue(check.matchesGeneration(active, pipeline, original));
    Object replacement = new Object();
    assertFalse(check.matchesGeneration(replacement, pipeline, changed));
    assertTrue(check.matchesGeneration(new Object(), pipeline, original));
    assertFalse(
        check.matchesGeneration(active, vignette().withShaderDefine("CHANGED").build(), original));
  }

  private static RenderPipeline.Builder vignette() {
    return RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("cbbg", "test/neutral_vignette"))
        .withVertexShader("core/position_tex_color")
        .withFragmentShader("core/position_tex_color")
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withDepthStencilState(Optional.empty())
        .withColorTargetState(
            new ColorTargetState(
                Optional.of(
                    new BlendFunction(
                        BlendFactor.ZERO,
                        BlendFactor.ONE_MINUS_SRC_COLOR,
                        BlendFactor.ZERO,
                        BlendFactor.ONE)),
                GpuFormat.RGBA8_UNORM,
                15));
  }

  private static String shader(String extension) throws IOException {
    try (var input =
        Objects.requireNonNull(
            NeutralVignetteTest.class.getResourceAsStream(
                "/assets/minecraft/shaders/core/position_tex_color." + extension))) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static ShaderSource configs(String vertex, String fragment) throws Exception {
    // Use the engine's private immutable source record without constructing a GPU device/cache.
    var key =
        Class.forName("net.minecraft.client.renderer.ShaderManager$ShaderSourceKey")
            .getDeclaredConstructor(Identifier.class, ShaderType.class);
    key.setAccessible(true);
    Identifier shader = Identifier.withDefaultNamespace("core/position_tex_color");
    var sources =
        Map.of(
            key.newInstance(shader, ShaderType.VERTEX), vertex,
            key.newInstance(shader, ShaderType.FRAGMENT), fragment);
    var constructor =
        Class.forName("net.minecraft.client.renderer.ShaderManager$Configs")
            .getDeclaredConstructor(Map.class, Map.class, Map.class);
    constructor.setAccessible(true);
    return (ShaderSource) constructor.newInstance(sources, Map.of(), Map.of());
  }
}
