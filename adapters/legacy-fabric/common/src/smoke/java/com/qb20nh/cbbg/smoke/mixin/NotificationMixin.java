package com.qb20nh.cbbg.smoke.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.qb20nh.cbbg.LegacyNotifications;
import com.qb20nh.cbbg.smoke.LegacyNotificationParity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LegacyNotifications.class, remap = false)
public abstract class NotificationMixin {
    @Inject(method = "generation", at = @At("HEAD"))
    private static void cbbg$notification(boolean complete, CallbackInfo ci) {
        if (complete) LegacyNotificationParity.completed++;
        else LegacyNotificationParity.started++;
    }

    @WrapOperation(method = "generation", at = @At(value = "INVOKE",
            target = "Lcom/qb20nh/cbbg/LegacyToasts;show(Lnet/minecraft/text/Text;Lnet/minecraft/text/Text;)V"))
    private static void cbbg$toast(Text title, Text message, Operation<Void> original) {
        LegacyNotificationParity.toasts++;
        original.call(title, message);
    }
}
