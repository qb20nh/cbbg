package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class ReleaseShaderFailureTarget {
  private static final String OWNER = "com.qb20nh.cbbg.render.DitherController";

  private ReleaseShaderFailureTarget() {}

  static @Nullable TextureTarget render(Minecraft client) {
    return (TextureTarget)
        invoke(
            "com.mojang.blaze3d.pipeline.TextureTarget screenshot(com.mojang.blaze3d.textures.GpuTextureView)",
            new Class<?>[] {GpuTextureView.class},
            new Object[] {
              Objects.requireNonNull(ReleaseViewport.mainTarget(client).getColorTextureView())
            });
  }

  static boolean disabled(Minecraft client) {
    return (boolean)
        Objects.requireNonNull(invoke("boolean isDisabled()", new Class<?>[0], new Object[0]));
  }

  private static @Nullable Object invoke(String member, Class<?>[] types, Object[] arguments) {
    try {
      Class<?> type = Class.forName(ReleaseMapping.className(OWNER));
      return type.getMethod(ReleaseMapping.memberName(OWNER, member), types)
          .invoke(null, arguments);
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException failure) {
      throw new LinkageError("Packaged shader failure API changed", failure);
    } catch (InvocationTargetException failure) {
      throw new LinkageError("Packaged shader failure API threw", failure.getCause());
    }
  }
}
