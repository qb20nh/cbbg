package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.qb20nh.cbbg.render.WorldClearScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GlStateManager.class)
public abstract class GlClearMixin {
  @SuppressWarnings("UnusedMethod") // MixinExtras invokes this wrapper.
  @WrapOperation(
      method = "_clear",
      at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glClear(I)V"))
  // skipcq: JAVA-W0324
  private static void cbbg$clear(int mask, Operation<Void> original) {
    if (!WorldClearScope.skip(mask)) original.call(mask);
  }
}
