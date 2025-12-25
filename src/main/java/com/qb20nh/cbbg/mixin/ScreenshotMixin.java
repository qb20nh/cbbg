package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.render.CbbgDither;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {

    private ScreenshotMixin() {}

    private static final ThreadLocal<Integer> CAPTURE_DEPTH = ThreadLocal.withInitial(() -> 0);

    @Inject(method = "takeScreenshot", at = @At("HEAD"), cancellable = true)
    private static void cbbg$takeScreenshot(RenderTarget target,
            CallbackInfoReturnable<NativeImage> cir) {
        if (CAPTURE_DEPTH.get() > 0) {
            return;
        }
        if (!CbbgClient.isEnabled()) {
            return;
        }

        if (target != Minecraft.getInstance().getMainRenderTarget()) {
            return;
        }

        RenderSystem.assertOnRenderThread();
        TextureTarget output = CbbgClient.isDemoMode() ? CbbgDither.renderDemoTarget(target)
                : CbbgDither.renderDitheredTarget(target);
        if (output == null) {
            return;
        }

        CAPTURE_DEPTH.set(CAPTURE_DEPTH.get() + 1);
        try {
            // Re-enter vanilla screenshot code, but read from the dithered RGBA8 target instead of
            // the HDR main target (which would otherwise get quantized without dithering during
            // readback).
            cir.setReturnValue(Screenshot.takeScreenshot(output));
        } finally {
            int next = CAPTURE_DEPTH.get() - 1;
            if (next <= 0) {
                CAPTURE_DEPTH.remove();
            } else {
                CAPTURE_DEPTH.set(next);
            }
        }
    }
}
