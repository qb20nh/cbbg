package com.qb20nh.cbbg.smoke.mixin;

import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.ShaderEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ShaderEffect.class)
public interface ShaderEffectAccessor {
    @Invoker("getTarget")
    Framebuffer cbbg$getTarget(String name);
}
