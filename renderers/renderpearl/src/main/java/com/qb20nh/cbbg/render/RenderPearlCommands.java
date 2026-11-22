package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class RenderPearlCommands {
  private RenderPearlCommands() {}

  static void uploadNoise(GpuDevice device, GpuTexture texture, NativeImage frame) {
    device.createCommandEncoder().writeToTexture(texture, frame);
  }

  static RenderPass openReadbackPass(RenderTarget output) {
    return RenderSystem.getDevice()
        .createCommandEncoder()
        .createRenderPass(
            () -> "CBBG screenshot conversion",
            Objects.requireNonNull(output.getColorTextureView()),
            Optional.empty());
  }

  static void bindReadbackPipeline(RenderPass pass) {
    pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
  }
}
