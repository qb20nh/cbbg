package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@org.jspecify.annotations.NullMarked
@Mixin(GpuSurface.class)
public interface GpuSurfaceAccessor {
  @Accessor("backend")
  GpuSurfaceBackend cbbg$backend();
}
