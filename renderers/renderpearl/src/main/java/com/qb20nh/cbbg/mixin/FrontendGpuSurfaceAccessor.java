package com.qb20nh.cbbg.mixin;

import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuSurface;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@NullMarked
@Mixin(FrontendGpuSurface.class)
public interface FrontendGpuSurfaceAccessor {
  @Accessor("backend")
  GpuSurfaceBackend cbbg$backend();
}
