package com.qb20nh.cbbg.banding;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.slf4j.LoggerFactory;

@NullMarked
public final class BandingPipelines {
  private static final Map<String, RenderPipeline> PIPELINES = new HashMap<>();
  private static final Set<String> FAILED = new HashSet<>();

  private BandingPipelines() {}

  public static CompiledRenderPipeline compile(
      RenderPipeline ordinary, Function<RenderPipeline, CompiledRenderPipeline> compile,
      boolean window) {
    String selected = BandingDebug.shader();
    if (selected != null) {
      String name = window ? selected + "_window" : selected;
      if (!FAILED.contains(name)) {
        try {
          return compile.apply(PIPELINES.computeIfAbsent(name, BandingPipelines::pipeline));
        } catch (RuntimeException failure) {
          FAILED.add(name);
          LoggerFactory.getLogger("cbbg_banding_debug").warn("Could not load {}", name, failure);
        }
      }
    }
    return compile.apply(ordinary);
  }

  private static RenderPipeline pipeline(String name) {
    return RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("cbbg_banding_debug", "pipeline/" + name))
        .withVertexShader("core/screenquad")
        .withFragmentShader(Identifier.fromNamespaceAndPath("cbbg_banding_debug", "core/" + name))
        .withBindGroupLayout(
            BindGroupLayout.builder()
                .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("NoiseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("CbbgDitherInfo", UniformType.UNIFORM_BUFFER)
                .build())
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withCull(false)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .build();
  }
}
