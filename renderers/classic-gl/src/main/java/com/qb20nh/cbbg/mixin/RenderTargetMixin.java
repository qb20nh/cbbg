package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.qb20nh.cbbg.render.ClassicDither;
import com.qb20nh.cbbg.render.ClassicFramebuffers;
import java.nio.IntBuffer;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {
  @WrapMethod(method = "_blitToScreen")
  private void cbbg$shaderBlit(
      int width, int height, boolean disableBlend, Operation<Void> original) {
    if (disableBlend && ClassicDither.needsShaderPresentation((RenderTarget) (Object) this)) {
      // Sodium replaces the non-blending path with a shader-free framebuffer copy.
      // Preserve its blend state while retaining the shader path for our final pass.
      GlStateManager._disableBlend();
      original.call(width, height, false);
    } else {
      original.call(width, height, disableBlend);
    }
  }

  @ModifyExpressionValue(
      method = "_blitToScreen",
      at =
          @At(
              value = "FIELD",
              target =
                  "Lnet/minecraft/client/renderer/GameRenderer;blitShader:Lnet/minecraft/client/renderer/ShaderInstance;"))
  private ShaderInstance cbbg$present(ShaderInstance original) {
    return ClassicDither.forPresentation((RenderTarget) (Object) this, original);
  }

  @WrapOperation(
      method = "createBuffers",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/blaze3d/platform/GlStateManager;_texImage2D(IIIIIIIILjava/nio/IntBuffer;)V"))
  private void cbbg$color(
      int target,
      int level,
      int internalFormat,
      int width,
      int height,
      int border,
      int format,
      int type,
      IntBuffer pixels,
      Operation<Void> original) {
    if (ClassicFramebuffers.owns((RenderTarget) (Object) this) && format == GL11.GL_RGBA) {
      ClassicFramebuffers.allocateColor(
          internalFormat,
          selected ->
              original.call(target, level, selected, width, height, border, format, type, pixels));
    } else {
      // Preserve custom colors and readable depth, including Satin's initFbo injections.
      original.call(target, level, internalFormat, width, height, border, format, type, pixels);
    }
  }
}
