package com.qb20nh.cbbg.debug;

import com.mojang.blaze3d.textures.GpuTexture;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.FloatAttachments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Live CBBG state for Minecraft 26.1's configurable F3 overlay. */
@NullMarked
public final class CbbgDebugEntry implements DebugScreenEntry {
  @Override
  public void display(
      DebugScreenDisplayer displayer,
      @Nullable Level level,
      @Nullable LevelChunk clientChunk,
      @Nullable LevelChunk serverChunk) {
    CbbgConfig.Mode user = CbbgConfig.get().mode();
    CbbgConfig.Mode effective = CbbgClient.getEffectiveMode();
    Minecraft client = Minecraft.getInstance();
    GpuTexture main = client.getMainRenderTarget().getColorTexture();
    GpuTexture lightmap = client.gameRenderer.levelLightmap().texture();
    displayer.addLine(
        "cbbg: mode="
            + effective
            + " (user="
            + user
            + ") iris="
            + (IrisCompat.isShaderPackActive() ? 1 : 0)
            + " dis="
            + (DitherController.isDisabled() ? 1 : 0));
    displayer.addLine(
        "cbbg: main="
            + (main == null ? "?" : format(FloatAttachments.internalFormat(main)))
            + " lm="
            + format(FloatAttachments.internalFormat(lightmap))
            + " fb="
            + framebufferEncoding()
            + " srgb="
            + (GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB) ? 1 : 0)
            + " stbn="
            + DitherController.getCurrentStbnFrameIndex()
            + "/"
            + DitherController.getStbnFrames());
  }

  private static String format(int internal) {
    return switch (internal) {
      case GL11.GL_RGBA8 -> "RGBA8";
      case GL30.GL_RGBA16F -> "RGBA16F";
      case GL30.GL_RGBA32F -> "RGBA32F";
      default -> "0x" + Integer.toHexString(internal).toUpperCase(java.util.Locale.ROOT);
    };
  }

  private static String framebufferEncoding() {
    if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != 0) return "?";
    int encoding =
        GL30.glGetFramebufferAttachmentParameteri(
            GL30.GL_DRAW_FRAMEBUFFER,
            GL11.GL_BACK_LEFT,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING);
    return encoding == GL30.GL_SRGB ? "SRGB" : encoding == GL11.GL_LINEAR ? "LIN" : "?";
  }
}
