package com.qb20nh.cbbg.smoke.mixin;

import com.qb20nh.cbbg.render.Gl2DitherProgram;
import com.qb20nh.cbbg.smoke.LegacySmoke;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = Gl2DitherProgram.class, remap = false)
public abstract class ShaderFailureMixin {
    @ModifyArg(method = "compile", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL20;glShaderSource(ILjava/lang/CharSequence;)V"), index = 1)
    private static CharSequence cbbg$shaderSource(CharSequence source) {
        return LegacySmoke.shaderSource(source);
    }
}
