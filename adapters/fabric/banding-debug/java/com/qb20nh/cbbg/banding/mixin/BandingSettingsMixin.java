package com.qb20nh.cbbg.banding.mixin;

import com.qb20nh.cbbg.banding.BandingDebug;
import com.qb20nh.cbbg.config.gui.CbbgConfigScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NullMarked
@Mixin(CbbgConfigScreen.class)
abstract class BandingSettingsMixin extends Screen {
  protected BandingSettingsMixin(Component title) {
    super(title);
  }

  @Inject(method = "init()V", at = @At("TAIL"))
  private void cbbg$addBandingControls(CallbackInfo ci) {
    int x = width / 2 - 130;
    int y = Math.max(0, height / 2 - 151);
    addRenderableWidget(
        Button.builder(BandingDebug.layoutLabel(), button -> {
          BandingDebug.cycleLayout();
          button.setMessage(BandingDebug.layoutLabel());
        }).bounds(x, y, 151, 20)
            .tooltip(Tooltip.create(Component.translatable("cbbg.banding.layout.tooltip")))
            .build());
    addRenderableWidget(
        Button.builder(BandingDebug.viewLabel(), button -> {
          BandingDebug.cycleView();
          button.setMessage(BandingDebug.viewLabel());
        }).bounds(x + 153, y, 107, 20)
            .tooltip(Tooltip.create(Component.translatable("cbbg.banding.view.tooltip")))
            .build());
  }
}
