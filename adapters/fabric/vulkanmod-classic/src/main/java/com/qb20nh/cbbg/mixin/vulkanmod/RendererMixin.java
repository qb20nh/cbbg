package com.qb20nh.cbbg.mixin.vulkanmod;

import com.qb20nh.cbbg.render.vulkanmod.VulkanModMainPass;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;
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
    var pass = new VulkanModMainPass(renderer, renderer.getMainPass());
    renderer.setMainPass(pass);
    ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(pass);
  }
}
