package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.Map;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PostChain.class)
public interface ReleasePostTargetsAccess {
  @Accessor("customRenderTargets")
  Map<String, RenderTarget> cbbg$targets();
}
