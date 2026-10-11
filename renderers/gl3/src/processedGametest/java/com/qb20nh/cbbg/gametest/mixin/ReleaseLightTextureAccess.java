package com.qb20nh.cbbg.gametest.mixin;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightTexture.class)
public interface ReleaseLightTextureAccess {
  @Accessor("lightTexture")
  DynamicTexture cbbg$texture();
}
