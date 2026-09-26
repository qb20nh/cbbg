package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;

/** Exercises allocation through Minecraft's targets with the optimized mod installed. */
@NullMarked
public final class ReleaseAllocationGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    JsonObject original = ReleaseClient.settings();
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      try {
        ReleaseClient.command(context, "mode set enabled");
        for (String precision : new String[] {"rgba32f", "rgba16f"}) {
          ReleaseClient.command(context, "format set " + precision);
          context.waitTicks(3);
          List<GpuFormat> order =
              precision.equals("rgba32f")
                  ? List.of(GpuFormat.RGBA32_FLOAT, GpuFormat.RGBA16_FLOAT, GpuFormat.RGBA8_UNORM)
                  : List.of(GpuFormat.RGBA16_FLOAT, GpuFormat.RGBA8_UNORM);
          context.runOnClient(
              client -> {
                for (int rejected = 0; rejected < order.size(); rejected++) {
                  checkSuccess(order, rejected, false);
                  checkSuccess(order, rejected, true);
                }
                checkTerminalFailure(order, false);
                checkTerminalFailure(order, true);
                checkDimensionCleanup(order.getFirst());
                checkUnrelatedTarget();
              });
        }
      } finally {
        ReleaseClient.command(
            context,
            "format set " + original.get("pixelFormat").getAsString().toLowerCase(Locale.ROOT));
        ReleaseClient.command(
            context, "mode set " + original.get("mode").getAsString().toLowerCase(Locale.ROOT));
        context.waitTicks(3);
      }
    }
  }

  private static void checkSuccess(List<GpuFormat> order, int rejected, boolean resize) {
    MainTarget target = resize ? new MainTarget(2, 2) : null;
    GpuTexture previousColor = target == null ? null : target.getColorTexture();
    GpuTexture previousDepth = target == null ? null : target.getDepthTexture();
    try (AllocationDevice device = new AllocationDevice(rejected, false)) {
      try {
        if (resize) {
          Objects.requireNonNull(target).resize(3, 3);
        } else {
          target = new MainTarget(3, 3);
        }
        if (!device.attempts.equals(order.subList(0, rejected + 1))
            || Objects.requireNonNull(target.getColorTexture()).getFormat() != order.get(rejected)
            || target.getColorTexture().isClosed()
            || device.textures.size() != 2
            || device.views.size() != 2) {
          throw new AssertionError(
              "Wrong allocation fallback or redundant allocation: " + device.attempts);
        }
        if (resize
            && (!Objects.requireNonNull(previousColor).isClosed()
                || !Objects.requireNonNull(previousDepth).isClosed())) {
          throw new AssertionError("Resize retained previous attachments");
        }
      } finally {
        if (target != null) target.destroyBuffers();
        device.assertClosed();
      }
    }
  }

  // Exact exception and texture identities verify allocation failure and cleanup.
  @SuppressWarnings("ReferenceEquality")
  private static void checkTerminalFailure(List<GpuFormat> order, boolean resize) {
    MainTarget target = resize ? new MainTarget(2, 2) : null;
    GpuTexture previousColor = target == null ? null : target.getColorTexture();
    GpuTexture previousDepth = target == null ? null : target.getDepthTexture();
    try (AllocationDevice device = new AllocationDevice(Integer.MAX_VALUE, false)) {
      try {
        RuntimeException terminal = null;
        try {
          if (resize) Objects.requireNonNull(target).resize(3, 3);
          else target = new MainTarget(3, 3);
        } catch (RuntimeException failure) {
          terminal = failure;
        }
        int dimensions = resize ? 1 : 2;
        List<GpuFormat> expected = new ArrayList<>();
        for (int dimension = 0; dimension < dimensions; dimension++) expected.addAll(order);
        if (terminal == null
            || !device.attempts.equals(expected)
            || device.failures.size() != expected.size()) {
          throw new AssertionError(
              "Terminal allocation did not exhaust precision fallback: " + device.attempts);
        }
        // MainTarget's constructor swallows GPU OOM before trying its default dimensions.
        // Retain the injected objects to verify evidence even when the public error wraps none.
        for (int dimension = 0; dimension < dimensions; dimension++) {
          int start = dimension * order.size();
          var last = device.failures.get(start + order.size() - 1);
          Throwable[] suppressed = last.getSuppressed();
          if (suppressed.length != order.size() - 1) {
            throw new AssertionError("Lost earlier allocation failures", last);
          }
          for (int index = 0; index < suppressed.length; index++) {
            if (suppressed[index] != device.failures.get(start + index)) {
              throw new AssertionError("Changed earlier allocation failure identity/order", last);
            }
          }
          if (resize && terminal != last) {
            throw new AssertionError("Resize replaced terminal allocation failure", terminal);
          }
        }
        if (!resize && (device.textures.size() != 2 || !device.textures.getFirst().isClosed())) {
          throw new AssertionError(
              "Constructor retry failed to close its previous depth attachment");
        }
        if (resize
            && (!Objects.requireNonNull(previousColor).isClosed()
                || !Objects.requireNonNull(previousDepth).isClosed())) {
          throw new AssertionError("Failed resize retained previous attachments");
        }
      } finally {
        if (target != null) target.destroyBuffers();
        // Vanilla retains the final depth texture when the constructor throws and no
        // target handle is returned. The fixture owns that cleanup, not CBBG.
        if (!resize) device.closeResources();
        device.assertClosed();
      }
    }
  }

  // Exact texture identity verifies successful replacement after depth failure.
  @SuppressWarnings("ReferenceEquality")
  private static void checkDimensionCleanup(GpuFormat expected) {
    MainTarget target = null;
    try (AllocationDevice device = new AllocationDevice(0, true)) {
      try {
        target = new MainTarget(3, 3);
        if (!device.attempts.equals(List.of(expected, expected))
            || device.textures.size() != 3
            || !device.textures.getFirst().isClosed()
            || target.getColorTexture() != device.textures.get(1)
            || target.getColorTexture().isClosed()) {
          throw new AssertionError(
              "Failed dimension retained a color attachment or repeated precision probe");
        }
      } finally {
        if (target != null) target.destroyBuffers();
        device.assertClosed();
      }
    }
  }

  private static void checkUnrelatedTarget() {
    TextureTarget target = null;
    try (AllocationDevice device = new AllocationDevice(0, false)) {
      try {
        target = new TextureTarget("Main", 3, 3, GpuFormat.RGBA8_UNORM, null);
        GpuTexture previous = target.getColorTexture();
        target.resize(4, 4);
        if (!device.attempts.equals(List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM))
            || !Objects.requireNonNull(previous).isClosed()
            || Objects.requireNonNull(target.getColorTexture()).getFormat()
                != GpuFormat.RGBA8_UNORM) {
          throw new AssertionError("Unrelated target named Main received float attachments");
        }
      } finally {
        if (target != null) target.destroyBuffers();
        device.assertClosed();
      }
    }
  }

  /** The device replacement exists only for a synchronous render-thread fixture operation. */
  private static final class AllocationDevice implements AutoCloseable {
    final List<GpuFormat> attempts = new ArrayList<>();
    final List<GpuOutOfMemoryException> failures = new ArrayList<>();
    final List<GpuTexture> textures = new ArrayList<>();
    final List<GpuTextureView> views = new ArrayList<>();
    private final GpuDevice actual = RenderSystem.getDevice();
    private final Field field;
    private int depthAttempts;

    AllocationDevice(int rejected, boolean rejectFirstDepth) {
      try {
        field = RenderSystem.class.getDeclaredField("DEVICE");
        field.setAccessible(true);
        GpuDevice proxy =
            (GpuDevice)
                Proxy.newProxyInstance(
                    GpuDevice.class.getClassLoader(),
                    new Class<?>[] {GpuDevice.class},
                    (ignored, method, args) -> {
                      if (method.getName().equals("createTexture")) {
                        GpuFormat format = (GpuFormat) Objects.requireNonNull(args)[2];
                        if (format != GpuFormat.D32_FLOAT) {
                          attempts.add(format);
                          if (attempts.size() <= rejected) {
                            var failure =
                                new GpuOutOfMemoryException(
                                    "CBBG allocation fixture " + attempts.size());
                            failures.add(failure);
                            throw failure;
                          }
                        } else if (++depthAttempts == 1 && rejectFirstDepth) {
                          throw new GpuOutOfMemoryException("CBBG depth allocation fixture");
                        }
                      }
                      try {
                        Object result = method.invoke(actual, args);
                        if (result instanceof GpuTexture texture) textures.add(texture);
                        if (result instanceof GpuTextureView view) views.add(view);
                        return result;
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
        field.set(null, proxy);
      } catch (ReflectiveOperationException failure) {
        throw new LinkageError("Cannot replace Minecraft GPU device for fixture", failure);
      }
    }

    void assertClosed() {
      if (textures.stream().anyMatch(texture -> !texture.isClosed())
          || views.stream().anyMatch(view -> !view.isClosed())) {
        throw new AssertionError("Allocation fixture retained a texture or view");
      }
    }

    void closeResources() {
      for (GpuTextureView view : views) if (!view.isClosed()) view.close();
      for (GpuTexture texture : textures) if (!texture.isClosed()) texture.close();
    }

    @Override
    public void close() {
      Throwable closeFailure = null;
      try {
        closeResources();
      } catch (RuntimeException | Error failure) {
        closeFailure = failure;
      }
      try {
        field.set(null, actual);
      } catch (IllegalAccessException failure) {
        LinkageError restoreFailure =
            new LinkageError("Cannot restore Minecraft GPU device", failure);
        if (closeFailure != null) restoreFailure.addSuppressed(closeFailure);
        throw restoreFailure;
      }
      if (closeFailure instanceof RuntimeException failure) throw failure;
      if (closeFailure instanceof Error failure) throw failure;
    }
  }
}
