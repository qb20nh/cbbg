package com.qb20nh.cbbg.render;

import com.google.common.collect.MapMaker;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPassLayout;
import com.mojang.renderpearl.api.pipeline.RenderPassLayout.Subpass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;

/** Matches RenderPearl's layouts and pipelines to CBBG's float attachments. */
@NullMarked
public final class FloatRenderPasses {
  private static final Map<RenderPassLayout, Map<List<GpuFormat>, RenderPassLayout>> LAYOUTS =
      new MapMaker().weakKeys().makeMap();
  private static final Map<CompiledRenderPipeline, RenderPipeline> PIPELINES =
      new MapMaker().weakKeys().makeMap();

  private FloatRenderPasses() {}

  public static void remember(RenderPipeline source, CompiledRenderPipeline compiled) {
    PIPELINES.putIfAbsent(compiled, source);
  }

  public static CompiledRenderPipeline forSubpass(CompiledRenderPipeline pipeline, Subpass active) {
    if (pipeline instanceof FrontendRenderPipeline compiled && !compiled.subpass().equals(active)) {
      var variants = LAYOUTS.get(compiled.subpass().renderPassLayout());
      if (variants != null && variants.containsValue(active.renderPassLayout())) {
        var source = PIPELINES.get(pipeline);
        if (source != null) return RenderSystem.getCompiledPipeline(source, active);
      }
    }
    return pipeline;
  }

  public static RenderPassDescriptor forAttachments(RenderPassDescriptor descriptor) {
    var original = descriptor.layout();
    var expected = original.attachmentFormats();
    var attachments = descriptor.attachments();
    if (expected.size() != attachments.size()) return descriptor;
    List<GpuFormat> changed = null;
    for (int i = 0; i < expected.size(); i++) {
      var actual = attachments.get(i).textureView().texture().getFormat();
      if (isFloatReplacement(expected.get(i), actual)) {
        if (changed == null) changed = new ArrayList<>(expected);
        changed.set(i, actual);
      }
    }
    if (changed == null) return descriptor;
    var layout =
        LAYOUTS
            .computeIfAbsent(original, ignored -> new HashMap<>())
            .computeIfAbsent(List.copyOf(changed), formats -> withFormats(original, formats));
    return new RenderPassDescriptor(
        descriptor.label(), layout, attachments, descriptor.renderArea());
  }

  public static boolean isFloatReplacement(GpuFormat expected, GpuFormat actual) {
    return expected == GpuFormat.RGBA8_UNORM
        && (actual == GpuFormat.RGBA16_FLOAT || actual == GpuFormat.RGBA32_FLOAT);
  }

  private static RenderPassLayout withFormats(RenderPassLayout original, List<GpuFormat> formats) {
    var builder = RenderPassLayout.builder(original.name());
    var attachments = formats.stream().map(builder::withAttachment).toList();
    for (var source : original.subpasses()) {
      var destination = builder.withSubpass(source.name());
      var colors = source.colorAttachments();
      for (int i = 0; i < colors.size(); i++) {
        int index = colors.getByte(i);
        if (index >= 0) destination.withColorAttachment(attachments.get(index), i);
      }
      var inputs = source.inputAttachments();
      for (int i = 0; i < inputs.size(); i++) {
        int index = inputs.getByte(i);
        if (index >= 0) destination.withInputAttachment(attachments.get(index), i);
      }
      if (source.depthStencilAttachment() >= 0) {
        destination.withDepthAttachment(attachments.get(source.depthStencilAttachment()));
      }
    }
    return builder.build();
  }
}
