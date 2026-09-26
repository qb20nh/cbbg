package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;

/** Render-thread scope for vanilla blur's internal targets, including pooled allocations. */
public final class MenuBlurScope {
  private static GpuFormat format;

  private MenuBlurScope() {}

  public static GpuFormat format() {
    return format;
  }

  public static void run(GpuFormat requested, Runnable action) {
    RenderSystem.assertOnRenderThread();
    GpuFormat previous = format;
    format = requested;
    try {
      action.run();
    } finally {
      format = previous;
    }
  }

  public static RenderTargetDescriptor upgrade(RenderTargetDescriptor descriptor) {
    if (format == null
        || descriptor.color() == null
        || descriptor.color().format() != GpuFormat.RGBA8_UNORM) {
      return descriptor;
    }
    return new RenderTargetDescriptor(
        descriptor.width(),
        descriptor.height(),
        new RenderTargetDescriptor.TextureProperties(descriptor.color().clearColor(), format),
        descriptor.depth());
  }
}
