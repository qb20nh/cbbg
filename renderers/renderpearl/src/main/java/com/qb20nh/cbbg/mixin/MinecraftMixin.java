package com.qb20nh.cbbg.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleTargets;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
@NullMarked
public abstract class MinecraftMixin {
  @Inject(method = "renderFrame", at = @At("HEAD"))
  private void cbbg$prepareFrame(boolean advanceGameTime, CallbackInfo ci) {
    DitherController.beginFrame();
  }

  @ModifyArg(
      method = "renderFrame",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"),
      index = 1)
  private GpuTextureView cbbg$present(GpuTextureView input) {
    return DitherController.present(input);
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void cbbg$close(CallbackInfo ci) {
    DitherController.close();
    STBNGenerator.shutdown();
    RenderScaleTargets.close();
  }
}
