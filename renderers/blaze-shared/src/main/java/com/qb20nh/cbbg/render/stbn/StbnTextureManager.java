package com.qb20nh.cbbg.render.stbn;

import com.mojang.blaze3d.GpuOutOfMemoryException;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.IdentityHashMap;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Owns uploaded immutable noise frames and restores streaming if extra allocations fail. */
@NullMarked
public class StbnTextureManager implements AutoCloseable {
  private final IdentityHashMap<NativeImage, GpuTextureView> frames = new IdentityHashMap<>();
  private @Nullable GpuTexture texture;
  private @Nullable GpuTextureView view;
  private boolean streaming;
  private int currentWidth = -1;
  private int currentHeight = -1;

  public @Nullable GpuTextureView getView() {
    return view;
  }

  public @Nullable GpuTexture getTexture() {
    return texture;
  }

  public boolean isReady() {
    return texture != null && !texture.isClosed() && view != null && !view.isClosed();
  }

  public void ensureTexture(int width, int height) {
    if (width <= 0 || height <= 0) return;
    if (!isReady() || width != currentWidth || height != currentHeight) {
      close();
      select(createView(width, height));
      currentWidth = width;
      currentHeight = height;
    }
  }

  public void upload(NativeImage frame) {
    GpuTextureView cached = frames.get(frame);
    if (cached != null) {
      select(cached);
      return;
    }
    if (!streaming && !frames.isEmpty()) {
      @Nullable GpuTextureView uploaded;
      try {
        uploaded = createAndUpload(frame);
      } catch (GpuOutOfMemoryException failure) {
        streaming = true;
        var others = ownedViews();
        others.remove(Objects.requireNonNull(view));
        frames.clear();
        closeViews(others);
        uploaded = null;
      }
      if (uploaded != null) {
        frames.put(frame, uploaded);
        select(uploaded);
        return;
      }
    }
    GpuTextureView selected = Objects.requireNonNull(view);
    RenderSystem.getDevice().createCommandEncoder().writeToTexture(selected.texture(), frame);
    if (!streaming) frames.put(frame, selected);
  }

  private void select(GpuTextureView selected) {
    view = selected;
    texture = selected.texture();
  }

  private static GpuTextureView createView(int width, int height) {
    GpuTexture created = StbnTextureAllocation.create(width, height);
    try {
      return RenderSystem.getDevice().createTextureView(created);
    } catch (RuntimeException | Error failure) {
      try (created) {
        throw failure;
      }
    }
  }

  private static GpuTextureView createAndUpload(NativeImage frame) {
    GpuTextureView uploaded = createView(frame.getWidth(), frame.getHeight());
    try {
      RenderSystem.getDevice().createCommandEncoder().writeToTexture(uploaded.texture(), frame);
      return uploaded;
    } catch (RuntimeException | Error failure) {
      GpuTexture allocated = uploaded.texture();
      try (allocated;
          uploaded) {
        throw failure;
      }
    }
  }

  private IdentityHashMap<GpuTextureView, Boolean> ownedViews() {
    var views = new IdentityHashMap<GpuTextureView, Boolean>();
    for (GpuTextureView cached : frames.values()) views.put(cached, true);
    if (view != null) views.put(view, true);
    return views;
  }

  private static void closeViews(IdentityHashMap<GpuTextureView, Boolean> views) {
    @Nullable Throwable failure = null;
    for (GpuTextureView owned : views.keySet()) {
      GpuTexture allocated = owned.texture();
      try (allocated) {
        owned.close();
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
    view = null;
    texture = null;
    streaming = false;
    closeViews(views);
  }
}
