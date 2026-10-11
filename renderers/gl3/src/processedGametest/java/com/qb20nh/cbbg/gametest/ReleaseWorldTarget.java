package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;

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

  static int noise() {
    return (Integer)
        Objects.requireNonNull(
            ReleasePackagedFields.get("com.qb20nh.cbbg.render.DitherPresentation", "int texture"));
  }
}
