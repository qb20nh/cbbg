package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class DitherPipelines {
  static final RenderPipeline ENABLED = create(false);
  static final RenderPipeline DEMO = create(true);

  private DitherPipelines() {}

  private static RenderPipeline create(boolean demo) {
    return RenderPipeline.builder()
        .withLocation(
            Identifier.fromNamespaceAndPath(
                "cbbg", demo ? "pipeline/cbbg_demo" : "pipeline/cbbg_dither"))
        .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
        .withFragmentShader(
            Identifier.fromNamespaceAndPath("cbbg", demo ? "core/cbbg_demo" : "core/cbbg_dither"))
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
