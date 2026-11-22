package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class NoiseTextureUpload {
  private NoiseTextureUpload() {}

  static void write(GpuDevice device, GpuTexture texture, NativeImage frame) {
    device
        .createCommandEncoder()
        .writeToTexture(
            texture, frame.getPixelBytes(), 0, 0, 0, 0, frame.getWidth(), frame.getHeight());
  }
}
