package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class ReleaseShutdownResources {
  private ReleaseShutdownResources() {}

  static @Nullable GpuTextureView noiseView() {
    Object noise =
        ReleaseShutdownGameTest.field(
            "com.qb20nh.cbbg.render.DitherPresentation",
            null,
            "com.qb20nh.cbbg.render.stbn.StbnTextureManager NOISE");
    return noise == null
        ? null
        : (GpuTextureView)
            ReleaseShutdownGameTest.field(
                "com.qb20nh.cbbg.render.stbn.StbnTextureManager",
                noise,
                "com.mojang.blaze3d.textures.GpuTextureView view");
  }

  private static List<GpuTextureView> noiseViews() {
    Object noise =
        ReleaseShutdownGameTest.field(
            "com.qb20nh.cbbg.render.DitherPresentation",
            null,
            "com.qb20nh.cbbg.render.stbn.StbnTextureManager NOISE");
    if (noise == null) return List.of();
    Map<?, ?> frames =
        (Map<?, ?>)
            Objects.requireNonNull(
                ReleaseShutdownGameTest.field(
                    "com.qb20nh.cbbg.render.stbn.StbnTextureManager",
                    noise,
                    "java.util.IdentityHashMap frames"));
    IdentityHashMap<GpuTextureView, Boolean> views = new IdentityHashMap<>();
    for (Object value : frames.values()) {
      views.put((GpuTextureView) Objects.requireNonNull(value), true);
    }
    GpuTextureView current = noiseView();
    if (current != null) views.put(current, true);
    return List.copyOf(views.keySet());
  }

  static @Nullable Live live() {
    String presentation = "com.qb20nh.cbbg.render.DitherPresentation";
    List<GpuTextureView> noise = noiseViews();
    Object pass =
        ReleaseShutdownGameTest.field(presentation, null, "com.qb20nh.cbbg.render.DitherPass PASS");
    TextureTarget output =
        (TextureTarget)
            ReleaseShutdownGameTest.field(
                "com.qb20nh.cbbg.render.DitherPass",
                pass,
                "com.mojang.blaze3d.pipeline.TextureTarget output");
    GpuTextureView view = output == null ? null : output.getColorTextureView();
    return noise.isEmpty()
            || noise.stream().anyMatch(GpuTextureView::isClosed)
            || (view != null && view.isClosed())
        ? null
        : new Live(noise, view);
  }

  static int liveCount(Live live) {
    return 2 * live.noise().size() + (live.output() == null ? 0 : 2);
  }

  static boolean liveClosed(Live live) {
    return live.noise().stream().allMatch(view -> view.isClosed() && view.texture().isClosed())
        && (live.output() == null
            || (live.output().isClosed() && live.output().texture().isClosed()));
  }

  static Owned captureOwned() {
    String controller = "com.qb20nh.cbbg.render.DitherController";
    String presentation = "com.qb20nh.cbbg.render.DitherPresentation";
    Object pass =
        Objects.requireNonNull(
            ReleaseShutdownGameTest.field(
                presentation, null, "com.qb20nh.cbbg.render.DitherPass PASS"));
    GpuBuffer uniform =
        (GpuBuffer)
            Objects.requireNonNull(
                ReleaseShutdownGameTest.field(
                    "com.qb20nh.cbbg.render.DitherPass",
                    pass,
                    "com.mojang.blaze3d.buffers.GpuBuffer uniform"));
    NativeImage[] frames =
        (NativeImage[])
            Objects.requireNonNull(
                ReleaseShutdownGameTest.field(
                    controller, null, "com.mojang.blaze3d.platform.NativeImage[] frames"));
    return new Owned(List.of(uniform), List.copyOf(Arrays.asList(frames.clone())));
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

  record Live(List<GpuTextureView> noise, @Nullable GpuTextureView output) {}

  record Owned(List<GpuBuffer> buffers, List<NativeImage> frames) {}
}
