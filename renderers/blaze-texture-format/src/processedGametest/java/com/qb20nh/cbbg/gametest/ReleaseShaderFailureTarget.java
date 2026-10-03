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
  private static final String CONTROLLER_OWNER = "com.qb20nh.cbbg.render.DitherController";
  private static final String PRESENTATION_OWNER = "com.qb20nh.cbbg.render.DitherPresentation";

  private ReleaseShaderFailureTarget() {}

  static @Nullable TextureTarget render(Minecraft client) {
    return (TextureTarget)
        invoke(
            PRESENTATION_OWNER,
            "com.mojang.blaze3d.pipeline.TextureTarget screenshot(com.mojang.blaze3d.textures.GpuTextureView)",
            new Class<?>[] {GpuTextureView.class},
            new Object[] {
              Objects.requireNonNull(ReleaseViewport.mainTarget(client).getColorTextureView())
            });
  }

  static boolean disabled(Minecraft client) {
    return (boolean)
        Objects.requireNonNull(
            invoke(CONTROLLER_OWNER, "boolean isDisabled()", new Class<?>[0], new Object[0]));
  }

  private static @Nullable Object invoke(
      String owner, String member, Class<?>[] types, Object[] arguments) {
    try {
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      return type.getMethod(ReleaseMapping.memberName(owner, member), types)
          .invoke(null, arguments);
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException failure) {
      throw new LinkageError("Packaged shader failure API changed", failure);
    } catch (InvocationTargetException failure) {
      throw new LinkageError("Packaged shader failure API threw", failure.getCause());
    }
  }
}
