package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class RenderPlatform {
  private RenderPlatform() {}

  public static GpuTexture lightmap(Minecraft client) {
    return client.gameRenderer.levelLightmap().texture();
  }
}
