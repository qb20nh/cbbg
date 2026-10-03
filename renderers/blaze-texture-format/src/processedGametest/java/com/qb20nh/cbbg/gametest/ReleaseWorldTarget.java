package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.lang.reflect.Field;
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
    try {
      String owner = "com.qb20nh.cbbg.render.DitherController";
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      Field field =
          type.getDeclaredField(
              ReleaseMapping.memberName(owner, "com.mojang.blaze3d.textures.GpuTexture noise"));
      field.setAccessible(true);
      return (GpuTexture) field.get(null);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged 26.1 noise texture changed", failure);
    }
  }
}
