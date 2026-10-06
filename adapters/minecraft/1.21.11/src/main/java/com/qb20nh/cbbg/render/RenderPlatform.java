package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class RenderPlatform {
  private RenderPlatform() {}

  public static RenderPipeline.Builder rgba8(RenderPipeline.Builder builder) {
    return builder.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false);
  }

  public static GpuTexture lightmap(Minecraft client) {
    return client.gameRenderer.lightTexture().getTextureView().texture();
  }
}
