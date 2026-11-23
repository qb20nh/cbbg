package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.qb20nh.cbbg.render.LegacyFramebuffers;
import com.qb20nh.cbbg.render.LegacyDither;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @ModifyExpressionValue(method = "initializeGame", at = @At(value = "NEW",
            target = "(IIZ)Lnet/minecraft/client/gl/Framebuffer;"))
    private Framebuffer cbbg$mainTarget(Framebuffer target) {
        return LegacyFramebuffers.track(target);
    }

    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void cbbg$refreshTargets(CallbackInfo ci) {
        LegacyFramebuffers.tick();
        LegacyDither.tick();
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void cbbg$close(CallbackInfo ci) {
        LegacyDither.close();
    }

    @Inject(method = "reloadResources", at = @At("RETURN"))
    private void cbbg$resourcesReloaded(CallbackInfo ci) {
        LegacyDither.resourcesReloaded();
    }

    @WrapOperation(method = "runGameLoop", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gl/Framebuffer;draw(II)V"))
    private void cbbg$present(Framebuffer target, int width, int height, Operation<Void> original) {
        LegacyDither.present(width, () -> original.call(target, width, height));
    }
}
