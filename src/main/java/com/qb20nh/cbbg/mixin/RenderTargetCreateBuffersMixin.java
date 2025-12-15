package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.render.GlFormatOverride;
import com.qb20nh.cbbg.render.Rgba16fSupport;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderTarget.class)
public abstract class RenderTargetCreateBuffersMixin {

  @Redirect(
      method = "createBuffers",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"))
  private GpuTexture cbbg$createBuffers$createTexture(
      GpuDevice device,
      Supplier<String> label,
      int usage,
      TextureFormat format,
      int width,
      int height,
      int depthOrLayers,
      int mipLevels) {
    // MainTarget can be resized via the base RenderTarget.resize() path, which calls createBuffers().
    // Ensure the main color attachment stays RGBA16F even after window resizes.
    if (format == TextureFormat.RGBA8
        && (Object) this instanceof MainTarget
        && CbbgClient.isEnabled()
        && Rgba16fSupport.isEnabled()) {
      GlFormatOverride.pushMainTargetColor();
      try {
        return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
      } catch (Throwable t) {
        Rgba16fSupport.disable(t);
        return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
      } finally {
        GlFormatOverride.popMainTargetColor();
      }
    }

    return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
  }
}
