package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import org.joml.Vector4fc;
import org.jspecify.annotations.NullMarked;

/** Resolves float variants through Minecraft's cache, which owns their GPU lifetime. */
@NullMarked
public final class FloatPipelines {
  // Values never reference the compiled key, allowing retired shader caches to be collected.
  private static final Map<CompiledRenderPipeline, Source> SOURCES = new WeakHashMap<>();

  private FloatPipelines() {}

  public static synchronized void remember(
      RenderPipeline pipeline, CompiledRenderPipeline compiled) {
    if (compiled != null) {
      SOURCES.computeIfAbsent(compiled, ignored -> new Source(pipeline, new HashMap<>()));
    }
  }

  public static synchronized CompiledRenderPipeline forAttachments(
      CompiledRenderPipeline compiled,
      List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
    Source source = SOURCES.get(compiled);
    if (source == null || source.pipeline.getColorTargetStates().size() != attachments.size()) {
      return compiled;
    }
    Map<Integer, GpuFormat> changes = new HashMap<>();
    for (int i = 0; i < attachments.size(); i++) {
      var attachment = attachments.get(i);
      var state = source.pipeline.getColorTargetStates().get(i);
      if (attachment == null || state == null || state.format() != GpuFormat.RGBA8_UNORM) {
        continue;
      }
      GpuFormat actual = attachment.textureView().texture().getFormat();
      if (actual == GpuFormat.RGBA16_FLOAT || actual == GpuFormat.RGBA32_FLOAT) {
        changes.put(i, actual);
      }
    }
    if (changes.isEmpty()) {
      return compiled;
    }
    RenderPipeline variant =
        source.variants.computeIfAbsent(
            Map.copyOf(changes),
            key -> {
              RenderPipeline result = source.pipeline;
              // Attachment order gives each variant a deterministic diagnostic name.
              for (int i = 0; i < attachments.size(); i++) {
                if (key.containsKey(i)) {
                  result = PipelineFormats.withColorFormat(result, i, key.get(i));
                }
              }
              return result;
            });
    return RenderSystem.getCompiledPipeline(variant);
  }

  private record Source(
      RenderPipeline pipeline, Map<Map<Integer, GpuFormat>, RenderPipeline> variants) {}
}
