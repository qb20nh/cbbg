package com.qb20nh.cbbg.mixin;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightTexture.class)
public interface LightTextureAccessor {
    @Accessor("lightTexture")
    DynamicTexture cbbg$getLightTexture();
}
