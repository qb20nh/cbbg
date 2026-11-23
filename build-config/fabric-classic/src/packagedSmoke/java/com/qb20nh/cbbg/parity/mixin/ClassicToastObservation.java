package com.qb20nh.cbbg.parity.mixin;

import com.qb20nh.cbbg.parity.ClassicVulkanSettings;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SystemToast.class)
abstract class ClassicToastObservation {
  @Shadow private Component title;
  @Shadow private List<FormattedCharSequence> messageLines;

  @Inject(method = "render", at = @At("RETURN"))
  private void cbbg$observeRenderedToast(
      GuiGraphics graphics,
      ToastComponent toasts,
      long time,
      CallbackInfoReturnable<Toast.Visibility> result) {
    if (result.getReturnValue() == Toast.Visibility.SHOW) {
      ClassicVulkanSettings.observeToast(title, messageLines, time);
    }
  }
}
