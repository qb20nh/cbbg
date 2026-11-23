package com.qb20nh.cbbg.mixin;

import com.qb20nh.cbbg.LegacyCommands;
import java.util.List;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.entity.ai.pathing.PathNodeMaker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PathNodeMaker.class)
public abstract class CommandCompletionMixin {
    @Shadow protected List<String> field_13328;

    @Inject(method = "method_12184", at = @At("HEAD"), cancellable = true)
    private void cbbg$complete(String message, CallbackInfo ci) {
        if (!((Object) this instanceof ChatScreen.class_2843)) return;
        List<String> local = LegacyCommands.suggest(message);
        if (local == null) return;
        field_13328.addAll(local);
        ci.cancel();
    }
}
