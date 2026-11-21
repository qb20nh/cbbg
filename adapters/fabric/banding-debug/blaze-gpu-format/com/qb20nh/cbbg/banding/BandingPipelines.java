package com.qb20nh.cbbg.banding;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class BandingPipelines {
  private static final Map<String, RenderPipeline> PIPELINES = new HashMap<>();

  private BandingPipelines() {}

  public static @Nullable RenderPipeline select(boolean window) {
    String name = BandingDebug.shader();
    return name == null ? null
        : PIPELINES.computeIfAbsent(window ? name + "_window" : name, BandingPipelines::pipeline);
  }

  private static RenderPipeline pipeline(String name) {
    return RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("cbbg_banding_debug", "pipeline/" + name))
        .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("cbbg_banding_debug", "core/" + name))
        .withBindGroupLayout(
            BindGroupLayout.builder()
                .withSampler("InSampler")
                .withSampler("NoiseSampler")
                .withUniform("CbbgDitherInfo", UniformType.UNIFORM_BUFFER)
                .build())
        .withDepthStencilState(Optional.empty())
        .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15))
        .withVertexBinding(0, VertexFormat.builder(0).build())
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .build();
  }
}
