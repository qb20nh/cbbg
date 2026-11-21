package com.qb20nh.cbbg.render.vulkanmod;

import net.vulkanmod.vulkan.framebuffer.RenderPass;

public interface SceneFramebufferAccess {
  void cbbg$prepareHandles(RenderPass initial, RenderPass reload);

  void cbbg$discardUnsubmitted();
}
