package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class ReleaseShutdownResources {
  private ReleaseShutdownResources() {}

  static GpuTextureView @Nullable [] live() {
    String presentation = "com.qb20nh.cbbg.render.DitherPresentation";
    GpuTextureView noise =
        (GpuTextureView)
            ReleaseShutdownGameTest.field(
                presentation, null, "com.mojang.blaze3d.textures.GpuTextureView noiseView");
    Object pass =
        ReleaseShutdownGameTest.field(presentation, null, "com.qb20nh.cbbg.render.DitherPass PASS");
    TextureTarget output =
        (TextureTarget)
            ReleaseShutdownGameTest.field(
                "com.qb20nh.cbbg.render.DitherPass",
                pass,
                "com.mojang.blaze3d.pipeline.TextureTarget output");
    GpuTextureView view = output == null ? null : output.getColorTextureView();
    return noise == null || view == null || noise.isClosed() || view.isClosed()
        ? null
        : new GpuTextureView[] {noise, view};
  }

  static Owned captureOwned() {
    String controller = "com.qb20nh.cbbg.render.DitherController";
    String presentation = "com.qb20nh.cbbg.render.DitherPresentation";
    Object pass =
        Objects.requireNonNull(
            ReleaseShutdownGameTest.field(
                presentation, null, "com.qb20nh.cbbg.render.DitherPass PASS"));
    MappableRingBuffer uniform =
        (MappableRingBuffer)
            Objects.requireNonNull(
                ReleaseShutdownGameTest.field(
                    "com.qb20nh.cbbg.render.DitherPass",
                    pass,
                    "net.minecraft.client.renderer.MappableRingBuffer uniform"));
    NativeImage[] frames =
        (NativeImage[])
            Objects.requireNonNull(
                ReleaseShutdownGameTest.field(
                    controller, null, "com.mojang.blaze3d.platform.NativeImage[] frames"));
    return new Owned(
        List.copyOf(Arrays.asList(buffers(uniform).clone())),
        List.copyOf(Arrays.asList(frames.clone())));
  }

  static int ownedCount(Owned owned) {
    return owned.buffers().size() + owned.frames().size();
  }

  static boolean ownedClosed(Owned owned) {
    for (GpuBuffer buffer : owned.buffers()) {
      if (!buffer.isClosed()) return false;
    }
    for (NativeImage frame : owned.frames()) {
      if (!ReleaseWorldAccess.imageClosed(frame)) return false;
    }
    return true;
  }

  private static GpuBuffer[] buffers(MappableRingBuffer uniform) {
    try {
      Field field =
          MappableRingBuffer.class.getDeclaredField(
              ReleaseGameNames.field(MappableRingBuffer.class, "buffers"));
      field.setAccessible(true);
      return (GpuBuffer[]) Objects.requireNonNull(field.get(uniform));
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Minecraft uniform buffer layout changed", failure);
    }
  }

  record Owned(List<GpuBuffer> buffers, List<NativeImage> frames) {}
}
