package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.NullMarked;

/** Converts a float main target to RGBA8 before vanilla's packed screenshot readback. */
@NullMarked
public final class Rgba8Readback {
  private Rgba8Readback() {}

  public static void capture(
      RenderTarget source, int downscaleFactor, Consumer<NativeImage> callback) {
    Rgba8Capture.capture(
        source,
        downscaleFactor,
        callback,
        () ->
            new TextureTarget(
                "CBBG screenshot", source.width, source.height, false, GpuFormat.RGBA8_UNORM),
        output -> {
          try (RenderPass pass =
              RenderSystem.getDevice()
                  .createCommandEncoder()
                  .createRenderPass(
                      () -> "CBBG screenshot conversion",
                      Objects.requireNonNull(output.getColorTextureView()),
                      Optional.empty())) {
            pass.setPipeline(RenderPipelines.TRACY_BLIT);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture(
                "InSampler",
                Objects.requireNonNull(source.getColorTextureView()),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
          }
        });
  }
}
