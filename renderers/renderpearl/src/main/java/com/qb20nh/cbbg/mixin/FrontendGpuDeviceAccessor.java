package com.qb20nh.cbbg.mixin;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@NullMarked
@Mixin(FrontendGpuDevice.class)
public interface FrontendGpuDeviceAccessor {
  @Accessor("backend")
  GpuDeviceBackend cbbg$backend();
}
