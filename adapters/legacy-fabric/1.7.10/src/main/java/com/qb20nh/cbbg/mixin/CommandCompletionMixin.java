package com.qb20nh.cbbg.mixin;

import com.qb20nh.cbbg.LegacyCommands;
import java.util.List;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public abstract class CommandCompletionMixin {
    @Shadow private List<String> suggestions;

    @Inject(method = "requestAutocomplete", at = @At("HEAD"), cancellable = true)
    private void cbbg$complete(String message, String lastWord, CallbackInfo ci) {
        List<String> local = LegacyCommands.suggest(message);
        if (local == null) return;
        suggestions.addAll(local);
        ci.cancel();
    }
}
