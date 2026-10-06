package com.qb20nh.cbbg.gametest.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.qb20nh.cbbg.gametest.ReleaseAllocationGameTest;
import java.nio.IntBuffer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Zero-width storage makes only the fixture-owned framebuffer incomplete. */
@Mixin(GlStateManager.class)
public abstract class ReleaseAllocationFailureMixin {
  @ModifyVariable(method = "_texImage2D", at = @At("HEAD"), argsOnly = true, ordinal = 3)
  private static int cbbg$reject(
      int originalWidth,
      int textureTarget,
      int level,
      int internalFormat,
      int width,
      int height,
      int border,
      int format,
      int type,
      @Nullable IntBuffer pixels) {
    return ReleaseAllocationGameTest.reject(internalFormat) ? 0 : originalWidth;
  }
}
