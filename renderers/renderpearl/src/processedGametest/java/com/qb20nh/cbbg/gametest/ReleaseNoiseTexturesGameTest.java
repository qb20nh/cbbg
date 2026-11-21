package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.qb20nh.cbbg.reference.DitherReference;
import com.qb20nh.cbbg.render.DitherPass;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import org.joml.Vector4f;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Uses the optimized implementation without retaining internal methods for tests. */
@NullMarked
final class ReleaseNoiseTexturesGameTest {
  void run(ClientGameTestContext context) {
    checkReuse(context);
    for (String failure : List.of("texture", "view", "write")) checkFallback(context, failure);
    context.runOnClient(client -> checkInitialViewFailure());
    context.runOnClient(client -> checkCloseFailure());
  }

  // Distinct frames must keep distinct GPU resources, and a reused frame must keep its view.
  @SuppressWarnings("ReferenceEquality")
  private static void checkReuse(ClientGameTestContext context) {
    Fixture fixture = context.computeOnClient(client -> new Fixture(new Tracker()));
    try {
      GpuTextureView first =
          context.computeOnClient(client -> fixture.cache.upload(fixture.images[0]));
      checkPixels(context, fixture, first, 0);
      GpuTextureView second =
          context.computeOnClient(client -> fixture.cache.upload(fixture.images[1]));
      checkPixels(context, fixture, second, 255);
      context.runOnClient(
          client -> {
            if (first == second
                || fixture.cache.upload(fixture.images[0]) != first
                || fixture.cache.upload(fixture.images[1]) != second
                || fixture.tracker.writes != 2)
              throw new AssertionError("Cached noise was uploaded again or replaced another frame");
          });
      checkPixels(context, fixture, first, 0);
      checkPixels(context, fixture, second, 255);
    } finally {
      context.runOnClient(client -> fixture.close());
    }
  }

  // Streaming must retain the last working texture after additional allocations fail.
  @SuppressWarnings("ReferenceEquality")
  private static void checkFallback(ClientGameTestContext context, String failure) {
    Tracker tracker = new Tracker();
    tracker.failure = failure;
    Fixture fixture = context.computeOnClient(client -> new Fixture(tracker));
    try {
      GpuTextureView retained =
          context.computeOnClient(
              client -> {
                fixture.cache.upload(fixture.images[0]);
                return fixture.cache.upload(fixture.images[1]);
              });
      GpuTextureView fallback =
          context.computeOnClient(client -> fixture.cache.upload(fixture.images[2]));
      context.runOnClient(
          client -> {
            if (fallback != retained
                || !tracker.textures.getFirst().isClosed()
                || (tracker.textures.size() > 2 && !tracker.textures.get(2).isClosed()))
              throw new AssertionError(
                  "Allocation fallback leaked or replaced its streaming texture");
          });
      checkPixels(context, fixture, fallback, 127);
      int allocations = tracker.allocations;
      for (int index : new int[] {0, 1, 0}) {
        var selected =
            context.computeOnClient(client -> fixture.cache.upload(fixture.images[index]));
        checkPixels(context, fixture, selected, index == 0 ? 0 : 255);
      }
      if (tracker.allocations != allocations)
        throw new AssertionError("Streaming kept retrying failed cache allocations");
    } finally {
      context.runOnClient(client -> fixture.close());
    }
  }

  private static void checkInitialViewFailure() {
    Tracker tracker = new Tracker();
    tracker.failInitialView = true;
    try {
      Cache unexpected = new Cache(tracker.device());
      unexpected.close();
      throw new AssertionError("Initial texture-view failure was ignored");
    } catch (GpuOutOfMemoryException expected) {
      tracker.assertClosed();
    }
  }

  private static void checkCloseFailure() {
    Tracker tracker = new Tracker();
    tracker.failClose = true;
    try (NativeImage first = pixels(0);
        NativeImage second = pixels(255)) {
      Cache cache = new Cache(tracker.device());
      cache.upload(first);
      cache.upload(second);
      try {
        cache.close();
        throw new AssertionError("Close failure was ignored");
      } catch (IllegalStateException expected) {
        if (!"Injected close failure".equals(expected.getMessage())) throw expected;
        tracker.assertClosed();
      } finally {
        cache.close();
      }
    }
  }

  private static NativeImage pixels(int value) {
    NativeImage image = new NativeImage(2, 2, false);
    for (int y = 0; y < 2; y++)
      for (int x = 0; x < 2; x++) image.setPixelABGR(x, y, 0xff000000 | value * 0x010101);
    return image;
  }

  private static void checkPixels(
      ClientGameTestContext context, Fixture fixture, GpuTextureView noise, int value) {
    CompletableFuture<@Nullable Void> read =
        context.computeOnClient(
            client -> {
              var output =
                  fixture.pass.render(
                      Objects.requireNonNull(fixture.source.getColorTextureView()),
                      noise,
                      2,
                      1,
                      1,
                      false);
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

  private static final class Cache implements AutoCloseable {
    private static final String OWNER = "com.qb20nh.cbbg.render.NoiseTextures";
    private final Object instance;
    private final Method upload;
    private final Method close;

    Cache(GpuDevice device) {
      try {
        Class<?> type = Class.forName(ReleaseMapping.className(OWNER));
        var constructor = type.getDeclaredConstructor(GpuDevice.class, int.class);
        constructor.setAccessible(true);
        instance = constructor.newInstance(device, 2);
        upload =
            type.getDeclaredMethod(
                ReleaseMapping.memberName(
                    OWNER,
                    "com.mojang.renderpearl.api.textures.GpuTextureView"
                        + " upload(com.mojang.blaze3d.platform.NativeImage)"),
                NativeImage.class);
        upload.setAccessible(true);
        close = type.getDeclaredMethod("close");
        close.setAccessible(true);
      } catch (InvocationTargetException failure) {
        throw cause(failure);
      } catch (ReflectiveOperationException failure) {
        throw new LinkageError("Cannot inspect optimized noise textures", failure);
      }
    }

    GpuTextureView upload(NativeImage image) {
      return (GpuTextureView) Objects.requireNonNull(invoke(upload, image));
    }

    private @Nullable Object invoke(Method method, Object... arguments) {
      try {
        return method.invoke(instance, arguments);
      } catch (InvocationTargetException failure) {
        throw cause(failure);
      } catch (ReflectiveOperationException failure) {
        throw new LinkageError("Cannot invoke optimized noise textures", failure);
      }
    }

    private static RuntimeException cause(InvocationTargetException failure) {
      Throwable cause = Objects.requireNonNull(failure.getCause());
      if (cause instanceof RuntimeException exception) return exception;
      if (cause instanceof Error error) throw error;
      throw new LinkageError("Unexpected noise-texture exception", cause);
    }

    @Override
    public void close() {
      invoke(close);
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final Tracker tracker;
    private final Cache cache;
    private final NativeImage[] images = {pixels(0), pixels(255), pixels(127)};
    private final DitherPass pass = new DitherPass();
    private final TextureTarget source =
        new TextureTarget("CBBG cache fixture", 2, 2, GpuFormat.RGBA32_FLOAT, null);

    Fixture(Tracker tracker) {
      this.tracker = tracker;
      cache = new Cache(tracker.device());
      RenderSystem.getDevice()
          .createCommandEncoder()
          .clearColorTexture(
              Objects.requireNonNull(source.getColorTexture()),
              new Vector4f(127.25f / 255, 127.25f / 255, 127.25f / 255, 0.375f));
    }

    @Override
    public void close() {
      try (cache;
          pass) {
        source.destroyBuffers();
        for (var image : images) image.close();
      }
      tracker.assertClosed();
    }
  }

  private static final class Tracker {
    private final List<GpuTexture> textures = new ArrayList<>();
    private final List<GpuTextureView> views = new ArrayList<>();
    private String failure = "none";
    private boolean failInitialView;
    private boolean failClose;
    private int allocations;
    private int viewCalls;
    private int writes;
    private int closes;

    GpuDevice device() {
      GpuDevice actual = RenderSystem.getDevice();
      return (GpuDevice)
          Proxy.newProxyInstance(
              GpuDevice.class.getClassLoader(),
              new Class<?>[] {GpuDevice.class},
              (proxy, method, arguments) -> {
                if (method.getName().equals("createTexture")
                    && ++allocations == 3
                    && failure.equals("texture"))
                  throw new GpuOutOfMemoryException("Injected texture allocation failure");
                if (method.getName().equals("createTextureView")
                    && ((++viewCalls == 3 && failure.equals("view")) || failInitialView))
                  throw new GpuOutOfMemoryException("Injected view allocation failure");
                Object result = forward(actual, method, arguments);
                if (result instanceof GpuTexture texture) textures.add(texture);
                if (result instanceof GpuTextureView view) {
                  views.add(view);
                  if (failClose)
                    return Proxy.newProxyInstance(
                        GpuTextureView.class.getClassLoader(),
                        new Class<?>[] {GpuTextureView.class},
                        (owner, operation, parameters) -> {
                          Object value = forward(view, operation, parameters);
                          if (operation.getName().equals("close") && ++closes == 1)
                            throw new IllegalStateException("Injected close failure");
                          return value;
                        });
                }
                if (method.getName().equals("createCommandEncoder")) {
                  Class<?> api = method.getReturnType();
                  return Proxy.newProxyInstance(
                      api.getClassLoader(),
                      new Class<?>[] {api},
                      (owner, operation, parameters) -> {
                        if (operation.getName().equals("writeToTexture")
                            && ++writes == 3
                            && failure.equals("write"))
                          throw new GpuOutOfMemoryException("Injected upload failure");
                        return forward(Objects.requireNonNull(result), operation, parameters);
                      });
                }
                return result;
              });
    }

    private static @Nullable Object forward(
        Object target, Method method, @Nullable Object @Nullable [] arguments) throws Throwable {
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException failure) {
        throw Objects.requireNonNull(failure.getCause());
      }
    }

    void assertClosed() {
      if (textures.stream().anyMatch(texture -> !texture.isClosed())
          || views.stream().anyMatch(view -> !view.isClosed()))
        throw new AssertionError("Noise cache retained GPU resources");
    }
  }
}
