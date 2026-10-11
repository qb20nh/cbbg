package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import com.qb20nh.cbbg.render.FloatAttachments;
import com.qb20nh.cbbg.render.MainTargets;
import com.qb20nh.cbbg.render.MenuBlurScope;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {
  @WrapOperation(
      method = "createBuffers",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"))
  @SuppressWarnings("UnusedMethod") // Called through MixinExtras during attachment allocation.
  private GpuTexture cbbg$allocate(
      GpuDevice device,
      @Nullable Supplier<String> label,
      int usage,
      TextureFormat format,
      int width,
      int height,
      int depth,
      int levels,
      Operation<GpuTexture> original) {
    Supplier<GpuTexture> allocation =
        () -> original.call(device, label, usage, format, width, height, depth, levels);
    if (format != TextureFormat.RGBA8 || !CbbgClient.isEnabled()) return allocation.get();
    if (MainTargets.contains(this)
        || (label != null && RenderScaleCompat.isRenderScaleColorTextureLabel(label))) {
      return FloatAttachments.create(CbbgConfig.get().pixelFormat(), allocation);
    }
    PixelFormat blur = MenuBlurScope.format();
    String name = label == null ? null : label.get();
    return blur != null && name != null && name.startsWith("FBO ")
        ? FloatAttachments.create(blur, allocation)
        : allocation.get();
  }
}
