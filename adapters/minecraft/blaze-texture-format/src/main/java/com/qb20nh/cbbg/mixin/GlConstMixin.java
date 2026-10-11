package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.textures.TextureFormat;
import com.qb20nh.cbbg.render.FloatAttachments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GlConst.class)
public abstract class GlConstMixin {
  @Inject(method = "toGlInternalId", at = @At("HEAD"), cancellable = true)
  private static void cbbg$internalFormat(
      TextureFormat format, CallbackInfoReturnable<Integer> result) {
    Integer forced = FloatAttachments.forcedInternalFormat();
    if (format == TextureFormat.RGBA8 && forced != null) result.setReturnValue(forced);
  }
}
