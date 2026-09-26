package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.qb20nh.cbbg.render.FloatAttachments;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {
  @Redirect(
      method = "createBuffers",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/renderpearl/api/device/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;"))
  private GpuTexture cbbg$resizeMain(
      GpuDevice device,
      Supplier<String> label,
      int usage,
      GpuFormat format,
      int width,
      int height,
      int layers,
      int mips) {
    // The mixin is merged after ProGuard runs; keep the type check for runtime.
    return FloatAttachments.createMainOrOriginal(
        MainTarget.class.isInstance(this),
        device,
        label,
        usage,
        format,
        width,
        height,
        layers,
        mips);
  }
}
