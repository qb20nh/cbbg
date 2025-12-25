package com.qb20nh.cbbg.mixin;

import com.qb20nh.cbbg.CbbgClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {

    private GuiMixin() {}

    @Inject(method = "render", at = @At("TAIL"))
    private void cbbg$renderDemoLabels(GuiGraphics guiGraphics, DeltaTracker deltaTracker,
            CallbackInfo ci) {
        if (!CbbgClient.isDemoMode()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            return;
        }

        CbbgClient.renderDemoLabels(guiGraphics);
    }
}
