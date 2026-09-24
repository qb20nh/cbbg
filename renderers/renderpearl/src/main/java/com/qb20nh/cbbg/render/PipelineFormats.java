package com.qb20nh.cbbg.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.Locale;
import net.minecraft.resources.Identifier;

/** Copies pipeline state while changing only a selected color attachment format. */
public final class PipelineFormats extends RenderPipeline {
    private PipelineFormats(RenderPipeline source, int attachment, GpuFormat format) {
        super(Identifier.fromNamespaceAndPath("cbbg", "formats/" + source.getLocation().getNamespace()
                        + "/" + source.getLocation().getPath() + "/" + attachment + "/"
                        + format.name().toLowerCase(Locale.ROOT)),
                source.getShaders(), source.getShaderDefines(), source.getBindGroupLayouts(),
                replace(source, attachment, format), source.getDepthStencilState(),
                source.getPolygonMode(), source.isCull(),
                source.getVertexFormatBindings().toArray(VertexFormat[]::new),
                source.getPrimitiveTopology(), source.pushConstantSize(), source.getSortKey());
    }

    public static RenderPipeline withColorFormat(RenderPipeline source, int attachment,
            GpuFormat format) {
        ColorTargetState state = source.getColorTargetStates().get(attachment);
        if (state == null) {
            throw new IllegalArgumentException("Cannot replace an unused color attachment");
        }
        if (format.hasDepthAspect() || format.hasStencilAspect()) {
            throw new IllegalArgumentException("Expected a color format");
        }
        return state.format() == format ? source : new PipelineFormats(source, attachment, format);
    }

    private static ColorTargetState[] replace(RenderPipeline source, int attachment,
            GpuFormat format) {
        ColorTargetState[] states = source.getColorTargetStates().toArray(ColorTargetState[]::new);
        ColorTargetState original = states[attachment];
        states[attachment] = new ColorTargetState(original.blendFunction(), format, original.writeMask());
        return states;
    }
}
