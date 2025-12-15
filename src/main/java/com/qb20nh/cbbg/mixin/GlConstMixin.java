package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.textures.TextureFormat;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.render.GlFormatOverride;
import com.qb20nh.cbbg.render.Rgba16fSupport;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GlConst.class)
public abstract class GlConstMixin {

  private static final int GL_RGBA16F = GL30.GL_RGBA16F;

  @Inject(method = "toGlInternalId", at = @At("HEAD"), cancellable = true)
  private static void cbbg$toGlInternalId(
      TextureFormat textureFormat, CallbackInfoReturnable<Integer> cir) {
    if (!CbbgClient.isEnabled()
        || !Rgba16fSupport.isEnabled()
        || !GlFormatOverride.isMainTargetColor()) {
      return;
    }

    if (textureFormat == TextureFormat.RGBA8) {
      cir.setReturnValue(GL_RGBA16F);
    }
  }
}
