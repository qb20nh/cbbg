package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.GpuOutOfMemoryException;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.reference.DitherReference;
import com.qb20nh.cbbg.render.DitherPass;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Checks the installed cache with real textures and faults restricted to fixture operations. */
@NullMarked
public final class ReleaseNoiseTexturesGameTest {
  private static @Nullable Tracker active;

  void run(ClientGameTestContext context) {
    checkReuse(context);
    for (Fault fault : List.of(Fault.TEXTURE, Fault.VIEW, Fault.WRITE))
      checkFallback(context, fault);
    context.runOnClient(client -> checkInitialViewFailure());
    context.runOnClient(client -> checkUnexpectedFailure());
    JsonArray cases = new JsonArray();
    for (String name :
        List.of(
            "reuse-and-pixels",
            "texture-fallback",
            "view-fallback",
            "write-fallback",
            "initial-view-cleanup",
            "unexpected-failure-propagation")) cases.add(name);
    JsonObject evidence = new JsonObject();
    evidence.add("passed", cases);
    try {
      Files.writeString(
          Path.of(
              Objects.requireNonNull(System.getProperty("cbbg.test.evidence")), "noise-cache.json"),
          evidence + "\n");
    } catch (java.io.IOException failure) {
      throw new AssertionError("Could not save noise-cache results", failure);
    }
  }

  @SuppressWarnings("ReferenceEquality")
  private static void checkReuse(ClientGameTestContext context) {
    Fixture fixture = context.computeOnClient(client -> new Fixture());
    try {
      GpuTextureView first =
          context.computeOnClient(client -> fixture.cache.upload(fixture.images[0]));
      GpuTextureView second =
          context.computeOnClient(client -> fixture.cache.upload(fixture.images[1]));
      context.runOnClient(
          client -> {
            if (first == second
                || first.texture() == second.texture()
                || fixture.cache.upload(fixture.images[0]) != first
                || fixture.cache.upload(fixture.images[1]) != second
                || fixture.tracker.writes != 2)
              throw new AssertionError("Noise frames were replaced or uploaded again");
          });
      checkPixels(context, fixture, first, 0);
      checkPixels(context, fixture, second, 255);
    } finally {
      context.runOnClient(client -> fixture.close());
    }
  }

  @SuppressWarnings("ReferenceEquality")
  private static void checkFallback(ClientGameTestContext context, Fault fault) {
    Fixture fixture = context.computeOnClient(client -> new Fixture());
    try {
      GpuTextureView first =
          context.computeOnClient(client -> fixture.cache.upload(fixture.images[0]));
      GpuTextureView retained =
          context.computeOnClient(client -> fixture.cache.upload(fixture.images[1]));
      context.runOnClient(
          client -> {
            fixture.tracker.fault = fault;
            if (fixture.cache.upload(fixture.images[2]) != retained)
              throw new AssertionError("Allocation failure replaced the working noise texture");
            if (!first.isClosed() || !first.texture().isClosed() || fixture.tracker.injected != 1)
              throw new AssertionError(
                  "Fallback did not release the other frames or exercise its fault");
          });
      checkPixels(context, fixture, retained, 127);
      context.runOnClient(
          client -> {
            int attempts = fixture.tracker.allocations;
            if (fixture.cache.upload(fixture.images[0]) != retained
                || fixture.tracker.allocations != attempts)
              throw new AssertionError("Streaming resumed allocating textures");
          });
      checkPixels(context, fixture, retained, 0);
    } finally {
      context.runOnClient(client -> fixture.close());
    }
  }

  private static void checkInitialViewFailure() {
    Tracker tracker = new Tracker();
    try (Cache cache = new Cache(tracker)) {
      tracker.fault = Fault.VIEW;
      try {
        cache.ensure();
        throw new AssertionError("Initial view failure was hidden");
      } catch (GpuOutOfMemoryException expected) {
        if (tracker.injected != 1) throw new AssertionError("View fault was not exercised");
      }
    }
    tracker.assertClosed();
  }

  private static void checkUnexpectedFailure() {
    try (Fixture fixture = new Fixture()) {
      fixture.cache.upload(fixture.images[0]);
      fixture.tracker.fault = Fault.UNEXPECTED;
      try {
        fixture.cache.upload(fixture.images[1]);
        throw new AssertionError("An unexpected upload failure was hidden");
      } catch (IllegalStateException expected) {
        if (!"Injected unexpected upload failure".equals(expected.getMessage())
            || fixture.tracker.injected != 1)
          throw new AssertionError("Wrong upload failure", expected);
      }
    }
  }

  private static void checkPixels(
      ClientGameTestContext context, Fixture fixture, GpuTextureView noise, int value) {
    CompletableFuture<@Nullable Void> read =
        context.computeOnClient(
            client -> {
              var output = fixture.pass.render(fixture.sourceView, noise, 2, 1, 1, false);
              CompletableFuture<@Nullable Void> done = new CompletableFuture<>();
              Screenshot.takeScreenshot(
                  output,
                  image -> {
                    try (image) {
                      int channel = DitherReference.channel(127.25 / 255, value, 2, 0, 2, false);
                      int expected = 0xff000000 | channel * 0x010101;
                      for (int y = 0; y < 2; y++)
                        for (int x = 0; x < 2; x++)
                          if (image.getPixel(x, y) != expected)
                            throw new AssertionError("Cached noise pixels differ");
                      done.complete(null);
                    } catch (Throwable failure) {
                      done.completeExceptionally(failure);
                    }
                  });
              return done;
            });
    context.waitFor(client -> read.isDone(), 200);
    read.join();
  }

  private enum Fault {
    NONE,
    TEXTURE,
    VIEW,
    WRITE,
    UNEXPECTED
  }

  private static final class Tracker {
    final IdentityHashMap<GpuTexture, Boolean> textures = new IdentityHashMap<>();
    final List<GpuTextureView> views = new ArrayList<>();
    Fault fault = Fault.NONE;
    int allocations, writes, injected;

    @Nullable Object invoke(Method method, Object instance, Object... arguments) {
      if (active != null) throw new AssertionError("Nested noise fixture");
      active = this;
      try {
        return method.invoke(instance, arguments);
      } catch (InvocationTargetException failure) {
        Throwable cause = Objects.requireNonNull(failure.getCause());
        if (cause instanceof RuntimeException exception) throw exception;
        if (cause instanceof Error error) throw error;
        throw new LinkageError("Unexpected noise-cache failure", cause);
      } catch (ReflectiveOperationException failure) {
        throw new LinkageError("Cannot invoke packaged noise cache", failure);
      } finally {
        active = null;
      }
    }

    void fail(Fault expected) {
      if (fault != expected) return;
      fault = Fault.NONE;
      injected++;
      if (expected == Fault.UNEXPECTED)
        throw new IllegalStateException("Injected unexpected upload failure");
      throw new GpuOutOfMemoryException("Injected noise " + expected + " allocation failure");
    }

    void assertClosed() {
      if (textures.keySet().stream().anyMatch(texture -> !texture.isClosed())
          || views.stream().anyMatch(view -> !view.isClosed()))
        throw new AssertionError("Noise cache retained owned GPU resources");
    }
  }

  private static boolean noiseLabel(@Nullable Supplier<String> label) {
    if (label == null) return false;
    String value = label.get();
    return "cbbg / STBN".equals(value) || "CBBG STBN".equals(value);
  }

  public static void beforeTexture(@Nullable Supplier<String> label) {
    Tracker tracker = active;
    if (tracker != null && noiseLabel(label)) {
      tracker.allocations++;
      tracker.fail(Fault.TEXTURE);
    }
  }

  public static void textureCreated(@Nullable Supplier<String> label, GpuTexture texture) {
    Tracker tracker = active;
    if (tracker != null && noiseLabel(label)) tracker.textures.put(texture, true);
  }

  public static void beforeView(GpuTexture texture) {
    Tracker tracker = active;
    if (tracker != null && tracker.textures.containsKey(texture)) tracker.fail(Fault.VIEW);
  }

  public static void viewCreated(GpuTexture texture, GpuTextureView view) {
    Tracker tracker = active;
    if (tracker != null && tracker.textures.containsKey(texture)) tracker.views.add(view);
  }

  public static void beforeWrite(GpuTexture texture) {
    Tracker tracker = active;
    if (tracker != null && tracker.textures.containsKey(texture)) {
      tracker.fail(Fault.WRITE);
      tracker.fail(Fault.UNEXPECTED);
    }
  }

  public static void written(GpuTexture texture) {
    Tracker tracker = active;
    if (tracker != null && tracker.textures.containsKey(texture)) tracker.writes++;
  }

  private static final class Cache implements AutoCloseable {
    private static final String OWNER = "com.qb20nh.cbbg.render.stbn.StbnTextureManager";
    private final Object instance;
    private final Method ensure, upload, view, close;
    private final Tracker tracker;

    Cache(Tracker tracker) {
      this.tracker = tracker;
      try {
        Class<?> type = Class.forName(ReleaseMapping.className(OWNER));
        instance = type.getConstructor().newInstance();
        ensure =
            type.getMethod(
                ReleaseMapping.memberName(OWNER, "void ensureTexture(int,int)"),
                int.class,
                int.class);
        upload =
            type.getMethod(
                ReleaseMapping.memberName(
                    OWNER, "void upload(com.mojang.blaze3d.platform.NativeImage)"),
                NativeImage.class);
        view =
            type.getMethod(
                ReleaseMapping.memberName(
                    OWNER, "com.mojang.blaze3d.textures.GpuTextureView getView()"));
        close = type.getMethod(ReleaseMapping.memberName(OWNER, "void close()"));
      } catch (ReflectiveOperationException failure) {
        throw new LinkageError("Cannot inspect packaged noise cache", failure);
      }
    }

    void ensure() {
      tracker.invoke(ensure, instance, 2, 2);
    }

    GpuTextureView upload(NativeImage image) {
      tracker.invoke(upload, instance, image);
      return (GpuTextureView) Objects.requireNonNull(tracker.invoke(view, instance));
    }

    @Override
    public void close() {
      tracker.invoke(close, instance);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Tracker tracker = new Tracker();
    final Cache cache = new Cache(tracker);
    final NativeImage[] images = {pixels(0), pixels(255), pixels(127)};
    final DitherPass pass = new DitherPass();
    final GpuTexture source = ReleaseNoiseInput.create();
    final GpuTextureView sourceView = RenderSystem.getDevice().createTextureView(source);

    Fixture() {
      cache.ensure();
      float color = 127.25f / 255;
      ReleaseNoiseInput.clear(source, color);
    }

    private static NativeImage pixels(int value) {
      NativeImage image = new NativeImage(2, 2, false);
      for (int y = 0; y < 2; y++)
        for (int x = 0; x < 2; x++) image.setPixel(x, y, 0xff000000 | value * 0x010101);
      return image;
    }

    @Override
    public void close() {
      try (cache;
          pass;
          source;
          sourceView) {
        for (NativeImage image : images) image.close();
      }
      tracker.assertClosed();
    }
  }
}
