package com.qb20nh.cbbg.gametest.mixin;

import com.qb20nh.cbbg.gametest.ReleaseShutdownGameTest;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class ProcessedShutdownMixin {
    @Inject(method = "close", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Util;shutdownTimeSource()V", shift = At.Shift.BEFORE))
    private void cbbgTestShutdown(CallbackInfo ci) {
        ReleaseShutdownGameTest.beforeVanillaClose();
    }
}
