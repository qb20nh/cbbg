package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.renderpearl.api.GpuFormat;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.render.MenuBlurScope;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PostChain.class)
public abstract class PostChainMixin {
  @Unique private GpuFormat cbbg$lastBlurFormat;

  @WrapMethod(method = "process")
  private void cbbg$blurScope(
      RenderTarget main, GraphicsResourceAllocator allocator, Operation<Void> original) {
    PostChain self = (PostChain) (Object) this;
    if (!self.id().equals(Identifier.withDefaultNamespace("blur"))) {
      MenuBlurScope.run(null, () -> original.call(main, allocator));
      return;
    }
    GpuFormat format = main.getColorTexture() == null ? null : main.getColorTexture().getFormat();
    if (!CbbgClient.isEnabled()
        || (format != GpuFormat.RGBA16_FLOAT && format != GpuFormat.RGBA32_FLOAT)) {
      format = null;
    }
    if (format != cbbg$lastBlurFormat) {
      // Resource packs can make blur targets persistent; vanilla only compares their size.
      self.closePersistentTargets();
      cbbg$lastBlurFormat = format;
    }
    MenuBlurScope.run(format, () -> original.call(main, allocator));
  }

  @ModifyExpressionValue(
      method = "addToFrame",
      at = @At(value = "NEW", target = "com/mojang/blaze3d/resource/RenderTargetDescriptor"))
  private RenderTargetDescriptor cbbg$floatBlurDescriptor(RenderTargetDescriptor descriptor) {
    return ((PostChain) (Object) this).id().equals(Identifier.withDefaultNamespace("blur"))
        ? MenuBlurScope.upgrade(descriptor)
        : descriptor;
  }
}
