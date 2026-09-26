package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.resources.Identifier;
import org.joml.Vector4fc;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Matches vanilla RGBA8 pipelines to float render target attachments on Vulkan. */
@NullMarked
public final class FloatPipelines {
  private static final Map<RenderPipeline, Map<String, RenderPipeline>> VARIANTS =
      new WeakHashMap<>();

  private FloatPipelines() {}

  public static synchronized RenderPipeline forAttachments(
      RenderPipeline source,
      List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
    @Nullable ColorTargetState[] states = source.getColorTargetStates();
    if (states.length != attachments.size()) {
      return source;
    }

    @Nullable ColorTargetState[] changed = states.clone();
    StringBuilder key = new StringBuilder();
    for (int i = 0; i < states.length; i++) {
      var attachment = attachments.get(i);
      ColorTargetState state = states[i];
      if (attachment == null || state == null || state.format() != GpuFormat.RGBA8_UNORM) {
        continue;
      }
      GpuFormat actual = attachment.textureView().texture().getFormat();
      if (actual != GpuFormat.RGBA16_FLOAT && actual != GpuFormat.RGBA32_FLOAT) {
        continue;
      }
      changed[i] = new ColorTargetState(state.blendFunction(), actual, state.writeMask());
      key.append(i).append('-').append(actual.name().toLowerCase(Locale.ROOT)).append('_');
    }
    if (key.isEmpty()) {
      return source;
    }
    String suffix = key.toString();
    return VARIANTS
        .computeIfAbsent(source, ignored -> new HashMap<>())
        .computeIfAbsent(suffix, ignored -> new FloatPipeline(source, changed, suffix));
  }

  private static final class FloatPipeline extends RenderPipeline {
    private FloatPipeline(
        RenderPipeline source, @Nullable ColorTargetState[] states, String suffix) {
      super(
          Identifier.fromNamespaceAndPath(
              "cbbg",
              "formats/"
                  + source.getLocation().getNamespace()
                  + "/"
                  + source.getLocation().getPath()
                  + "/"
                  + suffix),
          source.getVertexShader(),
          source.getFragmentShader(),
          source.getShaderDefines(),
          source.getBindGroupLayouts(),
          states,
          source.getDepthStencilState(),
          source.getPolygonMode(),
          source.isCull(),
          source.getVertexFormatBindings(),
          source.getPrimitiveTopology(),
          source.getSortKey());
    }
  }
}
