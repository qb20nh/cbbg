package com.qb20nh.cbbg.mixin;

import com.google.gson.JsonElement;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.render.ClassicFramebuffers;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PostChain.class)
public abstract class PostChainMixin {
  @Shadow @Final private String name;
  @Unique private boolean cbbg$explicitFormat;

  @WrapMethod(method = "parseTargetNode")
  private void cbbg$preserveCustomFormat(JsonElement target, Operation<Void> original) {
    cbbg$explicitFormat = target.isJsonObject() && target.getAsJsonObject().has("satin:format");
    try {
      original.call(target);
    } finally {
      cbbg$explicitFormat = false;
    }
  }

  @ModifyExpressionValue(
      method = "addTempTarget",
      at = @At(value = "NEW", target = "com/mojang/blaze3d/pipeline/TextureTarget"))
  private TextureTarget cbbg$worldPrecision(TextureTarget target) {
    if (name.equals("minecraft:shaders/post/transparency.json") && !cbbg$explicitFormat) {
      ClassicFramebuffers.track(target);
      // Retain the original constructor and other mods' hooks, then upgrade its storage.
      if (CbbgClient.isEnabled()) target.resize(target.width, target.height, false);
    }
    return target;
  }
}
