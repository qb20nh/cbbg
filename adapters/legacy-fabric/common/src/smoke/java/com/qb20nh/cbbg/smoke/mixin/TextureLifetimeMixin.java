package com.qb20nh.cbbg.smoke.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.qb20nh.cbbg.render.Gl2NoiseTextures;
import com.qb20nh.cbbg.smoke.LegacySmoke;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = Gl2NoiseTextures.class, remap = false)
public abstract class TextureLifetimeMixin {
    @WrapOperation(method = "close", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL11;glDeleteTextures(I)V"))
    private void cbbg$deleted(int texture, Operation<Void> original) {
        original.call(texture);
        LegacySmoke.textureDeleted(texture);
    }
}
