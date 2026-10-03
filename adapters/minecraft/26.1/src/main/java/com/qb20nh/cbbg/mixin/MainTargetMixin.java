package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.FloatAttachments;
import com.qb20nh.cbbg.render.MainTargets;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MainTarget.class)
public abstract class MainTargetMixin {
  @Inject(method = "<init>", at = @At("RETURN"))
  private void cbbg$track(CallbackInfo info) {
    MainTargets.track((MainTarget) (Object) this);
  }

  @WrapOperation(
      method = "allocateColorAttachment",
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
    return CbbgClient.isEnabled() && format == TextureFormat.RGBA8
        ? FloatAttachments.create(CbbgConfig.get().pixelFormat(), allocation)
        : allocation.get();
  }
}
