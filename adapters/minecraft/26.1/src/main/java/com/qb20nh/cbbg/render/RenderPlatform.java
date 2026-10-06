package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class RenderPlatform {
  private RenderPlatform() {}

  public static RenderPipeline.Builder rgba8(RenderPipeline.Builder builder) {
    return builder
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withDepthStencilState(Optional.empty());
  }

  public static GpuTexture lightmap(Minecraft client) {
    return client.gameRenderer.levelLightmap().texture();
  }
}
