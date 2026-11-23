package com.qb20nh.cbbg.mixin;

import com.qb20nh.cbbg.LegacyCommands;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public abstract class ClientCommandMixin {
    @Inject(method = "method_6738", at = @At("HEAD"), cancellable = true)
    private void cbbg$command(String message, CallbackInfo ci) {
        if (LegacyCommands.execute(message)) {
            MinecraftClient.getInstance().inGameHud.getChatHud().addToMessageHistory(message);
            ci.cancel();
        }
    }
}
