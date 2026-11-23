package com.qb20nh.cbbg.banding.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.qb20nh.cbbg.banding.BandingShader;
import com.qb20nh.cbbg.render.CbbgShaders;
import net.minecraft.client.renderer.ShaderInstance;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@NullMarked
@Mixin(value = CbbgShaders.class, remap = false)
abstract class BandingShaderMixin {
  @ModifyReturnValue(method = "get", at = @At("RETURN"))
  private static @Nullable ShaderInstance cbbg$banding(@Nullable ShaderInstance ordinary) {
    return BandingShader.select(ordinary);
  }
}
