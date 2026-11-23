package com.qb20nh.cbbg.smoke.mixin;

import com.qb20nh.cbbg.smoke.LegacySmoke;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class SmokeMixin {
    @Inject(method = "initializeGame", at = @At("HEAD"))
    private void cbbg$initialize(CallbackInfo ci) {
        LegacySmoke.initialize();
    }

    @Inject(method = "runGameLoop", at = @At("RETURN"))
    private void cbbg$frame(CallbackInfo ci) {
        LegacySmoke.frame();
    }
}
