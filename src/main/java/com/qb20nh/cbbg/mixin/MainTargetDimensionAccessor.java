package com.qb20nh.cbbg.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.blaze3d.pipeline.MainTarget$Dimension")
public interface MainTargetDimensionAccessor {
    @Accessor("width")
    int cbbg$getWidth();

    @Accessor("height")
    int cbbg$getHeight();
}
