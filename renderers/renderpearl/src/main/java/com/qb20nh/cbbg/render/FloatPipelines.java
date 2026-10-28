package com.qb20nh.cbbg.render;

import com.google.common.collect.MapMaker;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joml.Vector4fc;
import org.jspecify.annotations.NullMarked;

/** Resolves float variants through Minecraft's cache, which owns their GPU lifetime. */
@NullMarked
public final class FloatPipelines {
  private static final Map<Integer, GpuFormat> SINGLE_RGBA16 = Map.of(0, GpuFormat.RGBA16_FLOAT);
  private static final Map<Integer, GpuFormat> SINGLE_RGBA32 = Map.of(0, GpuFormat.RGBA32_FLOAT);
  // Weak object keys avoid hashing the fields of compiled pipeline records.
  // Values never reference their compiled key, so closed shader caches can be collected.
  private static final Map<CompiledRenderPipeline, Source> SOURCES =
      new MapMaker().weakKeys().makeMap();

  private FloatPipelines() {}

  public static synchronized void remember(
      RenderPipeline pipeline, CompiledRenderPipeline compiled) {
    if (compiled != null && SOURCES.get(compiled) == null) {
      SOURCES.put(compiled, new Source(pipeline, new HashMap<>()));
    }
  }

  public static synchronized CompiledRenderPipeline forAttachments(
      CompiledRenderPipeline compiled,
      List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
    if (attachments.isEmpty()) return compiled;
    Source source = SOURCES.get(compiled);
    if (source == null || source.pipeline.getColorTargetStates().size() != attachments.size()) {
      return compiled;
    }
    Map<Integer, GpuFormat> changes;
    if (attachments.size() == 1) {
      var attachment = attachments.getFirst();
      var state = source.pipeline.getColorTargetStates().getFirst();
      if (attachment == null || state == null || state.format() != GpuFormat.RGBA8_UNORM)
        return compiled;
      GpuFormat actual = attachment.textureView().texture().getFormat();
      if (actual == GpuFormat.RGBA16_FLOAT) changes = SINGLE_RGBA16;
      else if (actual == GpuFormat.RGBA32_FLOAT) changes = SINGLE_RGBA32;
      else return compiled;
    } else {
      Map<Integer, GpuFormat> selected = new HashMap<>();
      for (int i = 0; i < attachments.size(); i++) {
        var attachment = attachments.get(i);
        var state = source.pipeline.getColorTargetStates().get(i);
        if (attachment == null || state == null || state.format() != GpuFormat.RGBA8_UNORM)
          continue;
        GpuFormat actual = attachment.textureView().texture().getFormat();
        if (actual == GpuFormat.RGBA16_FLOAT || actual == GpuFormat.RGBA32_FLOAT)
          selected.put(i, actual);
      }
      if (selected.isEmpty()) return compiled;
      changes = Map.copyOf(selected);
    }
    RenderPipeline variant = source.variants.get(changes);
    if (variant == null) {
      variant = source.pipeline;
      // Attachment order keeps variant names consistent with the original pipeline.
      for (int i = 0; i < attachments.size(); i++) {
        GpuFormat format = changes.get(i);
        if (format != null) variant = PipelineFormats.withColorFormat(variant, i, format);
      }
      source.variants.put(changes, variant);
    }
    return RenderSystem.getCompiledPipeline(variant);
  }

  private record Source(
      RenderPipeline pipeline, Map<Map<Integer, GpuFormat>, RenderPipeline> variants) {}
}
