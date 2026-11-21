package com.qb20nh.cbbg.mixin.vulkanmod;

import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = DefaultMainPass.class, remap = false)
public interface DefaultMainPassAccess {
  @Accessor("mainRenderPass")
  RenderPass cbbg$mainRenderPass();
}
