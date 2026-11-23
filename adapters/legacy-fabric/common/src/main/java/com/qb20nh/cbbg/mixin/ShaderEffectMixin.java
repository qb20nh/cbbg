package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.qb20nh.cbbg.render.LegacyFramebuffers;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.ShaderEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ShaderEffect.class)
public abstract class ShaderEffectMixin {
    @ModifyExpressionValue(method = "addTarget", at = @At(value = "NEW",
            target = "(IIZ)Lnet/minecraft/client/gl/Framebuffer;"))
    private Framebuffer cbbg$postTarget(Framebuffer target) {
        return LegacyFramebuffers.track(target);
    }
}
