package com.qb20nh.cbbg.render;

import com.google.common.collect.MapMaker;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joml.Vector4fc;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Resolves float variants through Minecraft's cache, which owns their GPU lifetime. */
@NullMarked
public final class FloatPipelines {
  private static final Map<Integer, GpuFormat> SINGLE_RGBA16 = Map.of(0, GpuFormat.RGBA16_FLOAT);
  private static final Map<Integer, GpuFormat> SINGLE_RGBA32 = Map.of(0, GpuFormat.RGBA32_FLOAT);
  // Weak object keys avoid hashing the fields of compiled pipeline records.
  // Values never reference their compiled key, so closed shader caches can be collected.
  private static final Map<CompiledRenderPipeline, Source> SOURCES =
      new MapMaker().weakKeys().makeMap();

  /** Metadata follows the frontend pipeline; Minecraft still owns compiled variants. */
  public interface SourceHolder {
    @Nullable Source cbbg$pipelineSource();

    void cbbg$pipelineSource(Source source);
  }

  private FloatPipelines() {}

  public static synchronized void remember(
      RenderPipeline pipeline, CompiledRenderPipeline compiled) {
    if (compiled instanceof SourceHolder holder) {
      if (holder.cbbg$pipelineSource() == null) {
        holder.cbbg$pipelineSource(new Source(pipeline, new HashMap<>()));
      }
    } else if (compiled != null && SOURCES.get(compiled) == null) {
      SOURCES.put(compiled, new Source(pipeline, new HashMap<>()));
    }
  }

  public static synchronized CompiledRenderPipeline forAttachments(
      CompiledRenderPipeline compiled,
      List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
    if (attachments.isEmpty()) return compiled;
    Source source =
        compiled instanceof SourceHolder holder
            ? holder.cbbg$pipelineSource()
            : SOURCES.get(compiled);
    if (source == null || source.pipeline.getColorTargetStates().size() != attachments.size()) {
      return compiled;
    }
    Map<Integer, GpuFormat> changes = colorOverrides(source.pipeline, attachments);
    if (changes.isEmpty()) return compiled;
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

  private static Map<Integer, GpuFormat> colorOverrides(
      RenderPipeline pipeline,
      List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
    var states = pipeline.getColorTargetStates();
    if (attachments.size() == 1) {
      GpuFormat override = floatOverride(attachments.getFirst(), states.getFirst());
      if (override == GpuFormat.RGBA16_FLOAT) return SINGLE_RGBA16;
      if (override == GpuFormat.RGBA32_FLOAT) return SINGLE_RGBA32;
      return Map.of();
    }
    Map<Integer, GpuFormat> selected = new HashMap<>();
    for (int i = 0; i < attachments.size(); i++) {
      GpuFormat override = floatOverride(attachments.get(i), states.get(i));
      if (override != null) selected.put(i, override);
    }
    return Map.copyOf(selected);
  }

  private static @Nullable GpuFormat floatOverride(
      RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>> attachment,
      @Nullable ColorTargetState state) {
    if (attachment == null || state == null || state.format() != GpuFormat.RGBA8_UNORM) return null;
    GpuFormat format = attachment.textureView().texture().getFormat();
    return format == GpuFormat.RGBA16_FLOAT || format == GpuFormat.RGBA32_FLOAT ? format : null;
  }

  public record Source(
      RenderPipeline pipeline, Map<Map<Integer, GpuFormat>, RenderPipeline> variants) {}
}
