package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Minecraft 26.2 access to the actual main target and the presented noise texture. */
@NullMarked
final class ReleaseWorldTarget {
  private ReleaseWorldTarget() {}

  static RenderTarget main(Minecraft client) {
    return client.gameRenderer.mainRenderTarget();
  }

  static boolean hudHidden(Minecraft client) {
    return client.gui.hud.isHidden();
  }

  static void hideHud(Minecraft client, boolean hidden) {
    if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
  }

  static byte[] mapped(GpuBuffer buffer, int length) {
    try (var view = buffer.map(true, false)) {
      byte[] bytes = new byte[length];
      view.data().get(0, bytes);
      return bytes;
    }
  }

  static @Nullable GpuTexture noise() {
    try {
      String owner = "com.qb20nh.cbbg.render.CbbgDither";
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      Field field =
          type.getDeclaredField(
              ReleaseMapping.memberName(
                  owner, "com.qb20nh.cbbg.render.stbn.StbnTextureManager stbnManager"));
      field.setAccessible(true);
      Object manager = Objects.requireNonNull(field.get(null));
      String managerOwner = "com.qb20nh.cbbg.render.stbn.StbnTextureManager";
      Method getTexture =
          manager
              .getClass()
              .getMethod(
                  ReleaseMapping.memberName(
                      managerOwner, "com.mojang.blaze3d.textures.GpuTexture getTexture()"));
      return (GpuTexture) getTexture.invoke(manager);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged 26.2 noise texture changed", failure);
    }
  }
}
