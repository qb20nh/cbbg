package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.render.CbbgLightTextureHooks;
import com.qb20nh.cbbg.render.GlFormatOverride;
import com.qb20nh.cbbg.render.Rgba16fSupport;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LightTexture.class)
public abstract class LightTextureMixin implements CbbgLightTextureHooks {

  @Shadow @Final @Mutable private GpuTexture texture;
  @Shadow @Final @Mutable private com.mojang.blaze3d.textures.GpuTextureView textureView;
  @Shadow private boolean updateLightTexture;

  @Redirect(
      method = "<init>",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"))
  private GpuTexture cbbg$createLightTexture(
      GpuDevice device,
      String label,
      int usage,
      TextureFormat format,
      int width,
      int height,
      int depthOrLayers,
      int mipLevels) {
    if (!CbbgClient.isEnabled() || !Rgba16fSupport.isEnabled()) {
      return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
    }

    GlFormatOverride.pushLightmap();
    try {
      return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
    } catch (Throwable t) {
      // If the driver doesn't like RGBA16F for this texture, fall back and keep cbbg alive.
      return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
    } finally {
      GlFormatOverride.popLightmap();
    }
  }

  @Override
  public void cbbg$recreateLightTexture(boolean highPrecision) {
    RenderSystem.assertOnRenderThread();
    GpuDevice device = RenderSystem.getDevice();

    // Drop existing GPU resources.
    this.texture.close();
    this.textureView.close();

    if (highPrecision && CbbgClient.isEnabled() && Rgba16fSupport.isEnabled()) {
      GlFormatOverride.pushLightmap();
      try {
        this.texture = device.createTexture("Light Texture", 12, TextureFormat.RGBA8, 16, 16, 1, 1);
      } finally {
        GlFormatOverride.popLightmap();
      }
    } else {
      this.texture = device.createTexture("Light Texture", 12, TextureFormat.RGBA8, 16, 16, 1, 1);
    }

    this.textureView = device.createTextureView(this.texture);
    device.createCommandEncoder().clearColorTexture(this.texture, -1);
    this.updateLightTexture = true;
  }
}
