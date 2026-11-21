package com.qb20nh.cbbg.banding.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.qb20nh.cbbg.banding.BandingDebug;
import com.qb20nh.cbbg.render.BandingOutputFormat;
import com.qb20nh.cbbg.render.DitherPass;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@NullMarked
@Mixin(DitherPass.class)
abstract class BandingShaderMixin {
  @Unique private static final Map<String, RenderPipeline> CBBG_BANDING = new HashMap<>();

  @SuppressWarnings("UnusedMethod") // Called by MixinExtras.
  @WrapOperation(
      method = "draw",
      at = @At(value = "INVOKE",
          target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
  private void cbbg$select(RenderPass pass, RenderPipeline ordinary, Operation<Void> original) {
    String name = BandingDebug.shader();
    if (name != null) {
      var pipeline = CBBG_BANDING.computeIfAbsent(name, BandingShaderMixin::cbbg$pipeline);
      if (RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
        original.call(pass, pipeline);
        return;
      }
    }
    original.call(pass, ordinary);
  }

  @Unique
  private static RenderPipeline cbbg$pipeline(String name) {
    return BandingOutputFormat.rgba8(RenderPipeline.builder())
        .withLocation(Identifier.fromNamespaceAndPath("cbbg_banding_debug", "pipeline/" + name))
        .withVertexShader("core/screenquad")
        .withFragmentShader(Identifier.fromNamespaceAndPath("cbbg_banding_debug", "core/" + name))
        .withSampler("InSampler")
        .withSampler("NoiseSampler")
        .withUniform("CbbgDitherInfo", UniformType.UNIFORM_BUFFER)
        .withCull(false)
        .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
        .build();
  }
}
