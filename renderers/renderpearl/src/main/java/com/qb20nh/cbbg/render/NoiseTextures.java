package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.IdentityHashMap;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Owns uploaded noise frames; allocation pressure restores single-texture streaming. */
@NullMarked
final class NoiseTextures implements AutoCloseable {
  private final GpuDevice device;
  private final IdentityHashMap<NativeImage, GpuTextureView> frames = new IdentityHashMap<>();
  private @Nullable GpuTextureView current;
  private boolean streaming;

  NoiseTextures(GpuDevice device, int size) {
    this.device = device;
    current = createView(size, size);
  }

  GpuTextureView view() {
    return Objects.requireNonNull(current, "Noise textures have been closed");
  }

  GpuTextureView upload(NativeImage frame) {
    var cached = frames.get(frame);
    if (cached != null) {
      current = cached;
      return cached;
    }
    if (!streaming && !frames.isEmpty()) {
      try {
        var uploaded = createAndUpload(frame);
        frames.put(frame, uploaded);
        current = uploaded;
        return uploaded;
      } catch (GpuOutOfMemoryException failure) {
        streaming = true;
        var others = ownedViews();
        others.remove(view());
        frames.clear();
        closeViews(others);
      }
    }
    var selected = view();
    device.createCommandEncoder().writeToTexture(selected.texture(), frame);
    if (!streaming) frames.put(frame, selected);
    return selected;
  }

  private GpuTextureView createView(int width, int height) {
    GpuTexture texture =
        device.createTexture("CBBG STBN", 5, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
    try {
      return device.createTextureView(texture);
    } catch (RuntimeException | Error failure) {
      try (texture) {
        throw failure;
      }
    }
  }

  private GpuTextureView createAndUpload(NativeImage frame) {
    GpuTextureView uploaded = createView(frame.getWidth(), frame.getHeight());
    try {
      device.createCommandEncoder().writeToTexture(uploaded.texture(), frame);
      return uploaded;
    } catch (RuntimeException | Error failure) {
      GpuTexture texture = uploaded.texture();
      try (texture;
          uploaded) {
        throw failure;
      }
    }
  }

  private IdentityHashMap<GpuTextureView, Boolean> ownedViews() {
    var views = new IdentityHashMap<GpuTextureView, Boolean>();
    for (var view : frames.values()) views.put(view, true);
    if (current != null) views.put(current, true);
    return views;
  }

  private static void closeViews(IdentityHashMap<GpuTextureView, Boolean> views) {
    @Nullable Throwable failure = null;
    for (var view : views.keySet()) {
      GpuTexture texture = view.texture();
      try (texture) {
        view.close();
      } catch (RuntimeException | Error closeFailure) {
        if (failure == null) failure = closeFailure;
        else failure.addSuppressed(closeFailure);
      }
    }
    if (failure instanceof RuntimeException exception) throw exception;
    if (failure instanceof Error error) throw error;
  }

  @Override
  public void close() {
    var views = ownedViews();
    frames.clear();
    current = null;
    closeViews(views);
  }
}
