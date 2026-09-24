package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.RenderPipelines;

/** Converts float attachments before vanilla's packed RGBA8 screenshot readback. */
public final class Rgba8Readback {
    private Rgba8Readback() {}

    public static void capture(RenderTarget source, int downscaleFactor,
            Consumer<NativeImage> callback) {
        RenderSystem.assertOnRenderThread();
        if (downscaleFactor <= 0 || source.width % downscaleFactor != 0
                || source.height % downscaleFactor != 0) {
            throw new IllegalArgumentException("Image size is not divisible by downscale factor");
        }
        GpuTextureView input = source.getColorTextureView();
        if (input == null) {
            throw new IllegalStateException("Tried to capture screenshot of an incomplete framebuffer");
        }
        TextureTarget output = new TextureTarget("CBBG screenshot", source.width,
                source.height, GpuFormat.RGBA8_UNORM, null);
        try {
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "CBBG screenshot conversion",
                            output.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("InSampler", input,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            Screenshot.takeScreenshot(output, downscaleFactor, image -> {
                try {
                    callback.accept(image);
                } finally {
                    output.destroyBuffers();
                }
            });
        } catch (RuntimeException | Error failure) {
            output.destroyBuffers();
            throw failure;
        }
    }
}
