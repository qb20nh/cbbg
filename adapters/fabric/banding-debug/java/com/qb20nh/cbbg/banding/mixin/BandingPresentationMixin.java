package com.qb20nh.cbbg.banding.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.qb20nh.cbbg.banding.BandingDebug;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

@NullMarked
@Mixin(targets = "com.qb20nh.cbbg.render.DitherController", remap = false)
abstract class BandingPresentationMixin {
  @WrapMethod(method = "renderFrame")
  private static @Nullable Object cbbg$present(
      boolean advance, Supplier<?> draw, Operation<@Nullable Object> original) {
    // Screenshots also draw through this controller, with advance=false.
    return BandingDebug.whilePresenting(advance, () -> original.call(advance, draw));
  }
}
