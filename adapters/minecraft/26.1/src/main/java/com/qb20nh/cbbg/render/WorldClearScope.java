package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.textures.GpuTexture;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/** The world pass clears the main target again before drawing into it. */
public final class WorldClearScope {
  private static boolean skip;

  private WorldClearScope() {}

  @SuppressWarnings("ReferenceEquality") // GPU textures are selected by identity.
  public static void begin(
      GpuTexture color, int colorValue, GpuTexture depth, double depthValue, boolean renderWorld) {
    skip = false;
    Minecraft client = Minecraft.getInstance();
    if (!renderWorld
        || !client.isGameLoadFinished()
        || client.level == null
        || !CbbgClient.isEnabled()
        || CbbgConfig.get().pixelFormat() == PixelFormat.RGBA8
        || RenderScaleCompat.isLoaded()
        || colorValue != 0
        || depthValue != 1.0) {
      return;
    }
    var main = client.getMainRenderTarget();
    skip = color == main.getColorTexture() && depth == main.getDepthTexture();
  }

  public static boolean skip(int mask) {
    if (!skip || mask != (GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT)) return false;
    skip = false;
    return true;
  }

  public static void end() {
    skip = false;
  }
}
