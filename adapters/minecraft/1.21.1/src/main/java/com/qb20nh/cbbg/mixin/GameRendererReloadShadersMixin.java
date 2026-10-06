package com.qb20nh.cbbg.mixin;

import com.qb20nh.cbbg.render.CbbgShaders;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererReloadShadersMixin {
  @Inject(method = "reloadShaders", at = @At("HEAD"))
  private void cbbg$clear(ResourceProvider resources, CallbackInfo info) {
    CbbgShaders.close();
  }

  @Inject(method = "reloadShaders", at = @At("TAIL"))
  private void cbbg$reload(ResourceProvider resources, CallbackInfo info) {
    CbbgShaders.reload(resources);
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void cbbg$close(CallbackInfo info) {
    CbbgShaders.close();
  }
}
