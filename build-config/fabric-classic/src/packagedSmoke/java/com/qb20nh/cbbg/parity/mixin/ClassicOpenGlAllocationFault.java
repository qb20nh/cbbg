package com.qb20nh.cbbg.parity.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leaves float storage undefined for the window-sized fixture, after the real capability probe. */
@Mixin(GlStateManager.class)
abstract class ClassicOpenGlAllocationFault {
  @Inject(method = "_texImage2D", at = @At("HEAD"), cancellable = true)
  private static void cbbg$skipFloatStorage(
      int target,
      int level,
      int internalFormat,
      int width,
      int height,
      int border,
      int format,
      int type,
      IntBuffer pixels,
      CallbackInfo ci) {
    String rejection = System.getProperty("cbbg.test.openGlAllocation");
    if (rejection == null
        || target != GL11.GL_TEXTURE_2D
        || level != 0
        || width != 803
        || height != 601
        || format != GL11.GL_RGBA
        || pixels != null) return;
    boolean full = internalFormat == GL30.GL_RGBA32F;
    if (full || ("both".equals(rejection) && internalFormat == GL30.GL_RGBA16F)) {
      if (GL11.glGetTexLevelParameteri(target, level, GL11.GL_TEXTURE_WIDTH) != 0) {
        throw new AssertionError("OpenGL allocation fixture reached previously defined storage");
      }
      System.out.println(
          "CBBG_CLASSIC_OPENGL_ALLOCATION_SKIPPED format="
              + (full ? "RGBA32F" : "RGBA16F")
              + " size="
              + width
              + "x"
              + height);
      ci.cancel();
    }
  }
}
