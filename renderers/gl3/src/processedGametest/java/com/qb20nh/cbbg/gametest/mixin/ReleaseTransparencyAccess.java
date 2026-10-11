package com.qb20nh.cbbg.gametest.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface ReleaseTransparencyAccess {
  @Accessor("transparencyChain")
  @Nullable PostChain cbbg$transparencyChain();
}
