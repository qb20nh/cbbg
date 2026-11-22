package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.qb20nh.cbbg.render.FloatRenderPasses;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@NullMarked
@Mixin(RenderPassDescriptor.Builder.class)
abstract class RenderPassDescriptorBuilderMixin {
  @ModifyExpressionValue(
      method =
          "withAttachment(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor$Attachment;)V",
      at = @At(value = "INVOKE", target = "Ljava/util/List;get(I)Ljava/lang/Object;"))
  // skipcq: JAVA-W0324
  private Object cbbg$allowFloatColor(
      Object expected, @Local(argsOnly = true) RenderPassDescriptor.Attachment attachment) {
    var actual = attachment.textureView().texture().getFormat();
    // Encoding replaces the layout using the complete attachment list.
    return FloatRenderPasses.isFloatReplacement((GpuFormat) expected, actual) ? actual : expected;
  }
}
