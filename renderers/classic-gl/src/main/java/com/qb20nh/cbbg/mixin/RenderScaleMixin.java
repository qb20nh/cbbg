package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RenderScale's same-size resize skips applying changed filter preferences. */
@Pseudo
@Mixin(targets = "dev.zelo.renderscale.RenderScale", remap = false)
public abstract class RenderScaleMixin {
  @Shadow public RenderTarget renderTarget;

  @Inject(method = "onResolutionChanged", at = @At("RETURN"))
  private void cbbg$refreshFilter(CallbackInfo ci) {
    if (renderTarget == null || renderTarget.getColorTextureId() < 0) return;
    int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      // Let RenderScale's own filter redirect apply its current preference.
      renderTarget.setFilterMode(renderTarget.filterMode);
    } finally {
      GlStateManager._bindTexture(texture);
    }
  }
}
