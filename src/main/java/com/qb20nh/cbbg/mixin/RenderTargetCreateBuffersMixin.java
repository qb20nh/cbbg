package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.GpuOutOfMemoryException;
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

  @Redirect(method = "createBuffers", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"))
  private GpuTexture cbbg$createBuffers$createTexture(
      GpuDevice device,
      Supplier<String> label,
      int usage,
      TextureFormat format,
      int width,
      int height,
      int depthOrLayers,
      int mipLevels) {
    // MainTarget can be resized via the base RenderTarget.resize() path, which
    // calls createBuffers().
    // Ensure the main color attachment stays RGBA16F even after window resizes /
    // initial sizing.
    if (format != TextureFormat.RGBA8
        || !((Object) this instanceof MainTarget)
        || !CbbgClient.isEnabled()
        || !Rgba16fSupport.isEnabled()) {
      return device.createTexture(label, usage, java.util.Objects.requireNonNull(format), width, height, depthOrLayers,
          mipLevels);
    }

    GpuTexture texture = null;
    GpuOutOfMemoryException oom = null;
    Exception failure = null;

    GlFormatOverride.pushMainTargetColor();
    try {
      texture = device.createTexture(label, usage, java.util.Objects.requireNonNull(format), width, height,
          depthOrLayers, mipLevels);
    } catch (GpuOutOfMemoryException e) {
      oom = e;
    } catch (Exception e) {
      failure = e;
    } finally {
      GlFormatOverride.popMainTargetColor();
    }

    if (oom != null) {
      throw oom;
    }

    if (failure != null) {
      Rgba16fSupport.disable(failure);
      return device.createTexture(label, usage, java.util.Objects.requireNonNull(format), width, height, depthOrLayers,
          mipLevels);
    }

    return texture;
  }
}
