package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderPass;

public final class WindowRenderPass {
  private WindowRenderPass() {}

  public static RenderPass create(GlCommandEncoder encoder, GlDevice device) {
    return new RenderPass(new GlRenderPass(encoder, device, false), device);
  }

  public static void finish() {
    GlStateManager._depthMask(true);
    GlStateManager._colorMask(15);
    GlStateManager._disableScissorTest();
  }
}
