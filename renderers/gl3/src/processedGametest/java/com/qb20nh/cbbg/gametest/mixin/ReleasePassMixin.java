package com.qb20nh.cbbg.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.qb20nh.cbbg.api.DitherOptions;
import com.qb20nh.cbbg.gametest.ReleaseObservations;
import com.qb20nh.cbbg.render.DitherPass;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DitherPass.class)
public abstract class ReleasePassMixin {
  @WrapMethod(
      method =
          "render(IIIILcom/qb20nh/cbbg/api/DitherOptions;)Lcom/mojang/blaze3d/pipeline/TextureTarget;")
  private TextureTarget cbbg$drawn(
      int input,
      int width,
      int height,
      int noise,
      DitherOptions options,
      Operation<TextureTarget> original) {
    TextureTarget result = original.call(input, width, height, noise, options);
    ReleaseObservations.drawn(result, noise);
    return result;
  }
}
