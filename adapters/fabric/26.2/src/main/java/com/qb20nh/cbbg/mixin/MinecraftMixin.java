package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.CbbgDither;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Unique private static CbbgConfig.Mode lastMode;
    @Unique private static CbbgConfig.PixelFormat lastPixelFormat;

    @Inject(method = "close", at = @At("HEAD"))
    private void cbbg$close(CallbackInfo ci) {
        CbbgDither.close();
    }

    @ModifyArg(method = "renderFrame", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"), index = 1)
    private GpuTextureView cbbg$presentVulkan(GpuTextureView input) {
        if (MainTargetFormatSupport.isOpenGl()) {
            return input;
        }

        CbbgConfig.Mode mode = CbbgClient.getEffectiveMode();
        CbbgConfig.PixelFormat pixelFormat = CbbgConfig.get().pixelFormat();
        boolean resize = false;
        if (lastMode != null && lastMode != mode) {
            CbbgDither.resetAfterToggle();
            resize = lastMode.isActive() != mode.isActive();
        }
        if (lastPixelFormat != null && lastPixelFormat != pixelFormat) {
            CbbgDither.resetAfterToggle();
            resize |= mode.isActive();
        }
        lastMode = mode;
        lastPixelFormat = pixelFormat;
        if (resize) {
            RenderSystem.queueFencedTask(() -> {
                Minecraft mc = Minecraft.getInstance();
                mc.gameRenderer.mainRenderTarget().resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            });
        }
        if (!mode.isActive()) {
            return input;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer.mainRenderTarget().getColorTextureView() != input) {
            return input;
        }
        TextureTarget output = mode == CbbgConfig.Mode.DEMO
                ? CbbgDither.renderDemoTarget(input) : CbbgDither.renderDitheredTarget(input);
        return output == null || output.getColorTextureView() == null
                ? input : output.getColorTextureView();
    }
}
