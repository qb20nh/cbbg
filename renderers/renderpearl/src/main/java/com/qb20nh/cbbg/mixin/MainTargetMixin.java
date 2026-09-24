package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.qb20nh.cbbg.render.FloatAttachments;
import com.qb20nh.cbbg.render.MainTargets;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MainTarget.class)
public abstract class MainTargetMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void cbbg$trackTarget(int width, int height, CallbackInfo ci) {
        MainTargets.track((MainTarget) (Object) this);
    }

    @Redirect(method = "allocateColorAttachment", at = @At(value = "INVOKE", target =
            "Lcom/mojang/renderpearl/api/device/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;"))
    private GpuTexture cbbg$allocateMain(GpuDevice device, Supplier<String> label, int usage,
            GpuFormat format, int width, int height, int layers, int mips) {
        return FloatAttachments.createMainOrOriginal(true, device, label, usage, format, width, height, layers, mips);
    }
}
