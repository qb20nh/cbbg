package com.qb20nh.cbbg.mixin;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.qb20nh.cbbg.render.FloatPipelines;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(FrontendRenderPass.class)
public abstract class FrontendRenderPassMixin {
    @Shadow @Final
    private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments;

    @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
    private CompiledRenderPipeline cbbg$matchFloatAttachments(CompiledRenderPipeline pipeline) {
        return IrisCompat.isShaderPackActive() ? pipeline
                : FloatPipelines.forAttachments(pipeline, colorAttachments);
    }
}
