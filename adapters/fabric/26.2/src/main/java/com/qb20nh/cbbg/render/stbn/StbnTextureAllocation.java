package com.qb20nh.cbbg.render.stbn;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class StbnTextureAllocation {
  private StbnTextureAllocation() {}

  static GpuTexture create(int width, int height) {
    return RenderSystem.getDevice()
        .createTexture(
            () -> "cbbg / STBN",
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RGBA8_UNORM,
            width,
            height,
            1,
            1);
  }
}
