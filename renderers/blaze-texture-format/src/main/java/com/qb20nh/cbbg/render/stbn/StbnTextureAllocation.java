package com.qb20nh.cbbg.render.stbn;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class StbnTextureAllocation {
  private StbnTextureAllocation() {}

  static GpuTexture create(int width, int height) {
    return RenderSystem.getDevice()
        .createTexture(() -> "CBBG STBN", 5, TextureFormat.RGBA8, width, height, 1, 1);
  }
}
