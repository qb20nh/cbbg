package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPassLayouts;
import net.minecraft.client.renderer.pipeline.RenderPipelines;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class RenderPearlCommands {
  private RenderPearlCommands() {}

  static void uploadNoise(GpuDevice device, GpuTexture texture, NativeImage frame) {
    device
        .createCommandEncoder()
        .writeToTexture(
            texture, frame.getPixelBytes(), 0, 0, 0, 0, frame.getWidth(), frame.getHeight());
  }

  static RenderPass openReadbackPass(RenderTarget output) {
    return RenderSystem.getDevice()
        .createCommandEncoder()
        .createRenderPass(
            () -> "CBBG screenshot conversion",
            RenderPassLayouts.COLOR_ONLY,
            Objects.requireNonNull(output.getColorTextureView()),
            Optional.empty());
  }

  static void bindReadbackPipeline(RenderPass pass) {
    pass.setPipeline(
        RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT, pass.activeSubpass()));
  }

  static void closeWindowResources() {
    // Subpass presentation uses Minecraft’s window blit and owns no window resources.
  }
}
