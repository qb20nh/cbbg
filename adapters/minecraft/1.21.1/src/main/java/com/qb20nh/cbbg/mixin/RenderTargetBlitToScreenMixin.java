package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.render.CbbgDither;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTarget.class)
public abstract class RenderTargetBlitToScreenMixin {
  @Inject(method = "blitToScreen(IIZ)V", at = @At("HEAD"), cancellable = true)
  @SuppressWarnings("ReferenceEquality") // Only the client's main framebuffer is presented.
  private void cbbg$present(int width, int height, boolean disableBlend, CallbackInfo info) {
    var target = (RenderTarget) (Object) this;
    if (target != Minecraft.getInstance().getMainRenderTarget() || !CbbgClient.isEnabled()) return;
    boolean presented =
        CbbgClient.isDemoMode()
            ? CbbgDither.blitToScreenWithDemo(target)
            : CbbgDither.blitToScreenWithDither(target);
    if (presented) info.cancel();
  }
}
