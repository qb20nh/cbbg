package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.CbbgDither;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import com.qb20nh.cbbg.render.MainTargets;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
@NullMarked
public abstract class MinecraftMixin {

  @Unique private static CbbgConfig.@Nullable Mode lastMode;
  @Unique private static CbbgConfig.@Nullable PixelFormat lastPixelFormat;

  @Unique
  private static void cbbg$rememberPresentationSettings(
      CbbgConfig.Mode mode, CbbgConfig.PixelFormat pixelFormat) {
    lastMode = mode;
    lastPixelFormat = pixelFormat;
  }

  @Inject(method = "renderFrame", at = @At("HEAD"))
  private void cbbg$prepareFrame(boolean advanceGameTime, CallbackInfo ci) {
    if (Minecraft.getInstance().gameRenderer.mainRenderTarget() instanceof MainTarget target) {
      MainTargets.track(target);
    }
    DitherController.beginFrame();
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void cbbg$close(CallbackInfo ci) {
    CbbgDither.close();
    STBNGenerator.shutdown();
  }

  @WrapOperation(
      method = "renderFrame",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"))
  @SuppressWarnings("UnusedMethod") // Invoked by MixinExtras at the main presentation call.
  private void cbbg$present(
      GpuSurface surface, CommandEncoder encoder, GpuTextureView input, Operation<Void> original) {
    GpuTextureView output = cbbg$presentVulkan(surface, input);
    try {
      original.call(surface, encoder, output);
    } finally {
      CbbgDither.setVulkanSurface(null);
    }
  }

  // Texture identity limits presentation interception to Minecraft's main render target.
  @SuppressWarnings("ReferenceEquality")
  private GpuTextureView cbbg$presentVulkan(GpuSurface surface, GpuTextureView input) {
    if (MainTargetFormatSupport.isOpenGl()) {
      return input;
    }

    CbbgConfig.Mode mode = CbbgClient.getEffectiveMode();
    CbbgConfig.PixelFormat pixelFormat = CbbgConfig.get().pixelFormat();
    boolean resize = false;
    if (lastMode != null && lastMode != mode) {
      CbbgDither.resetAfterToggle();
      resize = lastMode.isActive() != mode.isActive();
    }
    if (lastPixelFormat != null && lastPixelFormat != pixelFormat) {
      CbbgDither.resetAfterToggle();
      resize |= mode.isActive();
    }
    cbbg$rememberPresentationSettings(mode, pixelFormat);
    if (resize) {
      RenderSystem.queueFencedTask(
          () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.gameRenderer
                .mainRenderTarget()
                .resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            RenderScaleCompat.refreshTargets();
          });
    }
    if (!mode.isActive()) {
      return input;
    }

    Minecraft mc = Minecraft.getInstance();
    if (mc.gameRenderer.mainRenderTarget().getColorTextureView() != input) {
      return input;
    }
    if (CbbgDither.canPresentToVulkanWindow(surface, input)) {
      CbbgDither.setVulkanSurface(surface);
      return input;
    }
    TextureTarget output =
        mode == CbbgConfig.Mode.DEMO
            ? CbbgDither.renderDemoTarget(input)
            : CbbgDither.renderDitheredTarget(input);
    return output == null || output.getColorTextureView() == null
        ? input
        : output.getColorTextureView();
  }
}
