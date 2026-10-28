package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Vector4f;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseNoiseInput {
  private ReleaseNoiseInput() {}

  static GpuTexture create() {
    return RenderSystem.getDevice()
        .createTexture(
            "CBBG noise fixture",
            GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_COPY_DST
                | GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RGBA32_FLOAT,
            2,
            2,
            1,
            1);
  }

  static void clear(GpuTexture texture, float color) {
    RenderSystem.getDevice()
        .createCommandEncoder()
        .clearColorTexture(texture, new Vector4f(color, color, color, 1));
  }
}
