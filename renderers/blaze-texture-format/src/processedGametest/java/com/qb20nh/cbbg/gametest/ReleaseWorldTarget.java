package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Minecraft 26.1 access to the actual main target and the presented noise texture. */
@NullMarked
final class ReleaseWorldTarget {
  private ReleaseWorldTarget() {}

  static RenderTarget main(Minecraft client) {
    return client.getMainRenderTarget();
  }

  static boolean hudHidden(Minecraft client) {
    return client.options.hideGui;
  }

  static void hideHud(Minecraft client, boolean hidden) {
    client.options.hideGui = hidden;
  }

  static byte[] mapped(GpuBuffer buffer, int length) {
    try (var view =
        RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer, true, false)) {
      byte[] bytes = new byte[length];
      view.data().get(0, bytes);
      return bytes;
    }
  }

  static @Nullable GpuTexture noise() {
    var view = ReleaseShutdownResources.noiseView();
    return view == null ? null : view.texture();
  }
}
