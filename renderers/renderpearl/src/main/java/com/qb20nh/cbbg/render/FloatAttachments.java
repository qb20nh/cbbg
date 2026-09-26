package com.qb20nh.cbbg.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/** Allocates a color attachment using the shared precision fallback policy. */
@NullMarked
public final class FloatAttachments {
  private FloatAttachments() {}

  public static GpuTexture createMainOrOriginal(
      boolean mainTarget,
      GpuDevice device,
      Supplier<String> label,
      int usage,
      GpuFormat original,
      int width,
      int height,
      int depthOrLayers,
      int mipLevels) {
    if (mainTarget && original == GpuFormat.RGBA8_UNORM) {
      CbbgConfig config = CbbgConfig.get();
      if (CbbgClient.isEnabled()) {
        return create(
            device, label, usage, config.pixelFormat(), width, height, depthOrLayers, mipLevels);
      }
    }
    if (!mainTarget
        && MenuBlurScope.format() != null
        && original == MenuBlurScope.format()
        && label.get().startsWith("FBO ")) {
      return create(
          device,
          label,
          usage,
          original == GpuFormat.RGBA32_FLOAT ? PixelFormat.RGBA32F : PixelFormat.RGBA16F,
          width,
          height,
          depthOrLayers,
          mipLevels);
    }
    return device.createTexture(label, usage, original, width, height, depthOrLayers, mipLevels);
  }

  // Throwable rejects self-suppression, so compare exception instances.
  @SuppressWarnings("ReferenceEquality")
  public static GpuTexture create(
      GpuDevice device,
      Supplier<String> label,
      int usage,
      PixelFormat requested,
      int width,
      int height,
      int depthOrLayers,
      int mipLevels) {
    @Nullable GpuTexture[] allocated = new GpuTexture[1];
    List<RuntimeException> failures = new ArrayList<>();
    // A successful probe is the actual attachment, avoiding a second allocation.
    FormatPolicy.effective(
        requested,
        candidate -> {
          GpuFormat format =
              candidate == PixelFormat.RGBA32F ? GpuFormat.RGBA32_FLOAT : GpuFormat.RGBA16_FLOAT;
          try {
            allocated[0] =
                device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
            return true;
          } catch (RuntimeException failure) {
            failures.add(failure);
            LoggerFactory.getLogger("cbbg")
                .warn(
                    "Could not allocate {} as {}; trying lower precision",
                    label.get(),
                    format,
                    failure);
            return false;
          }
        });
    if (allocated[0] != null) {
      return allocated[0];
    }
    try {
      return device.createTexture(
          label, usage, GpuFormat.RGBA8_UNORM, width, height, depthOrLayers, mipLevels);
    } catch (RuntimeException failure) {
      for (RuntimeException earlier : failures) {
        if (earlier != failure) {
          failure.addSuppressed(earlier);
        }
      }
      throw failure;
    }
  }
}
