package com.qb20nh.cbbg.mixin.vulkanmod;

import com.qb20nh.cbbg.render.vulkanmod.VulkanModMainPass;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Renderer.class, remap = false)
abstract class RendererMixin {
  @Inject(method = "init", at = @At("TAIL"))
  private void cbbg$installMainPass(CallbackInfo ci) {
    Renderer renderer = (Renderer) (Object) this;
    renderer.setMainPass(new VulkanModMainPass(renderer, renderer.getMainPass()));
  }
}
