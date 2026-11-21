package com.qb20nh.cbbg.parity.mixin;

import com.qb20nh.cbbg.parity.ClassicVulkanWarning;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphics.class)
abstract class ClassicFormatWarningObservation {
  @Inject(
      method =
          "drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
      at = @At("RETURN"))
  private void cbbg$observeFormatWarning(
      Font font, Component text, int x, int y, int color, CallbackInfo ci) {
    ClassicVulkanWarning.observe(font, text, x, y, color);
  }
}
