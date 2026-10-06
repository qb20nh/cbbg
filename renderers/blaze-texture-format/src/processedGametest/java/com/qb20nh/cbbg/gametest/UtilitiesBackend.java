package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.qb20nh.cbbg.api.NoiseVolume;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class UtilitiesBackend {
  private UtilitiesBackend() {}

  static void checkBackend(
      net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext context) {
    ReleaseGraphics.check(context);
  }

  static @Nullable Screen screen(Minecraft client) {
    return client.screen;
  }

  static TextureTarget input(int width, int height) {
    var target = new TextureTarget("CBBG utility input", width, height, false);
    clear(target);
    return target;
  }

  static void clear(TextureTarget target) {
    RenderSystem.getDevice()
        .createCommandEncoder()
        .clearColorTexture(Objects.requireNonNull(target.getColorTexture()), 0xff7f7f7f);
  }

  static Noise noise(NoiseVolume volume, int frame) {
    var device = RenderSystem.getDevice();
    var texture =
        device.createTexture(
            () -> "CBBG utility noise",
            5,
            TextureFormat.RGBA8,
            volume.width(),
            volume.height(),
            1,
            1);
    try (NativeImage image = new NativeImage(volume.width(), volume.height(), false)) {
      for (int y = 0; y < volume.height(); y++) {
        for (int x = 0; x < volume.width(); x++) {
          image.setPixelABGR(x, y, volume.pixelABGR(x, y, frame));
        }
      }
      device.createCommandEncoder().writeToTexture(texture, image);
    }
    return new Noise(texture, device.createTextureView(texture));
  }

  record Noise(GpuTexture texture, GpuTextureView view) implements AutoCloseable {
    @Override
    public void close() {
      view.close();
      texture.close();
    }
  }
}
