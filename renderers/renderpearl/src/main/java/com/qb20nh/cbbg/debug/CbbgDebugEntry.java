package com.qb20nh.cbbg.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.compat.sulkan.SulkanCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

@NullMarked
public final class CbbgDebugEntry implements DebugScreenEntry {
  @Override
  public void display(
      @NonNull DebugScreenDisplayer displayer,
      @Nullable Level level,
      @Nullable LevelChunk clientChunk,
      @Nullable LevelChunk serverChunk) {
    var client = Minecraft.getInstance();
    var main = client.gameRenderer.mainRenderTarget().getColorTexture();
    var lightmap = client.gameRenderer.levelLightmap();
    String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
    displayer.addLine(
        "cbbg: mode="
            + CbbgClient.getEffectiveMode()
            + " (user="
            + CbbgConfig.get().mode()
            + ") iris="
            + (IrisCompat.isShaderPackActive() ? 1 : 0)
            + " dis="
            + (DitherController.isDisabled() ? 1 : 0)
            + " sulkan="
            + (SulkanCompat.isShaderPackActive() ? 1 : 0));
    displayer.addLine(
        "cbbg: main="
            + (main == null ? "?" : main.getFormat().name())
            + " lm="
            + lightmap.texture().getFormat().name()
            + " backend="
            + backend
            + framebufferState(backend)
            + " stbn="
            + DitherController.getCurrentStbnFrameIndex()
            + "/"
            + DitherController.getStbnFrames());
  }

  private static String framebufferState(String backend) {
    if (!"opengl".equalsIgnoreCase(backend)) return " fb=n/a srgb=n/a";
    int previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    int encoding;
    try {
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
      int attachment =
          GL11.glGetBoolean(GL11.GL_DOUBLEBUFFER) ? GL11.GL_BACK_LEFT : GL11.GL_FRONT_LEFT;
      encoding =
          GL30.glGetFramebufferAttachmentParameteri(
              GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING);
    } finally {
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous);
    }
    String name =
        encoding == GL21.GL_SRGB
            ? "SRGB"
            : encoding == GL11.GL_LINEAR ? "LIN" : "0x" + Integer.toHexString(encoding);
    return " fb=" + name + " srgb=" + (GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB) ? 1 : 0);
  }
}
