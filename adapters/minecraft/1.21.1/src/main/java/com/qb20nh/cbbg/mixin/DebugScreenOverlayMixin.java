package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.CbbgDither;
import com.qb20nh.cbbg.render.DitherController;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {
  @Inject(method = "getGameInformation", at = @At("RETURN"), cancellable = true)
  private void cbbg$information(CallbackInfoReturnable<List<String>> info) {
    var lines = new ArrayList<>(info.getReturnValue());
    var client = Minecraft.getInstance();
    var light = ((LightTextureAccessor) client.gameRenderer.lightTexture()).cbbg$getLightTexture();
    lines.add(
        "cbbg: mode="
            + CbbgClient.getEffectiveMode()
            + " (user="
            + CbbgConfig.get().mode()
            + ") iris="
            + (IrisCompat.isShaderPackActive() ? 1 : 0)
            + " dis="
            + (CbbgDither.isDisabled() ? 1 : 0));
    lines.add(
        "cbbg: main="
            + cbbg$format(client.getMainRenderTarget().getColorTextureId())
            + " lm="
            + cbbg$format(light.getId())
            + " srgb="
            + (GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB) ? 1 : 0)
            + " stbn="
            + DitherController.getCurrentStbnFrameIndex()
            + "/"
            + DitherController.getStbnFrames());
    info.setReturnValue(lines);
  }

  @Unique
  private static String cbbg$format(int texture) {
    if (texture <= 0) return "?";
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      GlStateManager._bindTexture(texture);
      int format =
          GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
      return switch (format) {
        case GL11.GL_RGBA8 -> "RGBA8";
        case GL30.GL_RGBA16F -> "RGBA16F";
        case GL30.GL_RGBA32F -> "RGBA32F";
        default -> "0x" + Integer.toHexString(format);
      };
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }
}
