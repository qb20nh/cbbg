package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.GpuOutOfMemoryException;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Exercises real main-target allocations and failure fallback in the optimized candidate. */
@NullMarked
public final class ReleaseAllocationGameTest implements FabricClientGameTest {
  private static final List<String> ATTEMPTS = new ArrayList<>();
  private static List<String> rejected = List.of();

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      JsonObject original = settings();
      CommandDispatcher<FabricClientCommandSource> dispatcher =
          Objects.requireNonNull(
              context.computeOnClient(client -> ReleaseCommands.getActiveDispatcher()));
      FabricClientCommandSource source = silentSource();
      try {
        context.runOnClient(
            client -> {
              command(dispatcher, source, "format set rgba32f");
              command(dispatcher, source, "mode set enabled");
            });
        context.waitTicks(3);
        context.runOnClient(
            client -> {
              checkResize();
              checkUsableFallback(
                  List.of("rgba32f"), ReleaseAllocationFormat.firstFallbackAttempts(), "rgba16f");
              checkUsableFallback(
                  List.of("rgba32f", "rgba16f"),
                  ReleaseAllocationFormat.secondFallbackAttempts(),
                  "rgba8");
              checkExhaustedAllocation();
              checkRecovery();
            });
      } finally {
        context.runOnClient(
            client -> {
              rejected = List.of();
              command(dispatcher, source, "mode set disabled");
              command(
                  dispatcher,
                  source,
                  "format set "
                      + original.get("pixelFormat").getAsString().toLowerCase(Locale.ROOT));
              command(
                  dispatcher,
                  source,
                  "mode set " + original.get("mode").getAsString().toLowerCase(Locale.ROOT));
            });
      }
    }
  }

  private static void checkResize() {
    MainTarget target = new MainTarget(3, 3);
    GpuTexture color = null;
    GpuTexture depth = null;
    GpuTextureView colorView = null;
    GpuTextureView depthView = null;
    try {
      GpuTexture oldColor = Objects.requireNonNull(target.getColorTexture());
      GpuTexture oldDepth = Objects.requireNonNull(target.getDepthTexture());
      GpuTextureView oldColorView = Objects.requireNonNull(target.getColorTextureView());
      GpuTextureView oldDepthView = Objects.requireNonNull(target.getDepthTextureView());
      assertFormat(oldColor, "rgba32f");
      target.resize(4, 4);
      if (!oldColor.isClosed()
          || !oldDepth.isClosed()
          || !oldColorView.isClosed()
          || !oldDepthView.isClosed()) {
        throw new AssertionError("Main-target resize retained old attachments");
      }
      assertFormat(Objects.requireNonNull(target.getColorTexture()), "rgba32f");
      if (target.width != 4 || target.height != 4) {
        throw new AssertionError("Main-target resize used wrong dimensions");
      }
      color = target.getColorTexture();
      depth = target.getDepthTexture();
      colorView = target.getColorTextureView();
      depthView = target.getDepthTextureView();
    } finally {
      target.destroyBuffers();
    }
    if (color == null
        || depth == null
        || colorView == null
        || depthView == null
        || !color.isClosed()
        || !depth.isClosed()
        || !colorView.isClosed()
        || !depthView.isClosed()) {
      throw new AssertionError("Destroy retained main-target attachments");
    }
  }

  private static void checkExhaustedAllocation() {
    ATTEMPTS.clear();
    rejected = List.of("rgba32f", "rgba16f", "rgba8");
    MainTarget target = null;
    RuntimeException failure = null;
    try {
      target = new MainTarget(3, 3);
    } catch (RuntimeException expected) {
      failure = expected;
    } finally {
      rejected = List.of();
      if (target != null) target.destroyBuffers();
    }
    if (failure == null)
      throw new AssertionError("All rejected allocations unexpectedly succeeded");
    List<String> expected = ReleaseAllocationFormat.exhaustedAttempts();
    if (!ATTEMPTS.equals(expected)) {
      throw new AssertionError("Wrong 32F/16F/8F fallback attempts: " + ATTEMPTS);
    }
  }

  private static void checkUsableFallback(
      List<String> rejectedFormats, List<String> expectedAttempts, String expectedFormat) {
    ATTEMPTS.clear();
    rejected = rejectedFormats;
    MainTarget target = null;
    GpuTexture color = null;
    GpuTexture depth = null;
    GpuTextureView colorView = null;
    GpuTextureView depthView = null;
    try {
      target = new MainTarget(3, 3);
      if (!ATTEMPTS.equals(expectedAttempts)) {
        throw new AssertionError("Wrong fallback allocation attempts: " + ATTEMPTS);
      }
      color = Objects.requireNonNull(target.getColorTexture());
      depth = Objects.requireNonNull(target.getDepthTexture());
      colorView = Objects.requireNonNull(target.getColorTextureView());
      depthView = Objects.requireNonNull(target.getDepthTextureView());
      assertFormat(color, expectedFormat);
      if (target.width != 3
          || target.height != 3
          || colorView.getWidth(0) != 3
          || colorView.getHeight(0) != 3
          || depthView.getWidth(0) != 3
          || depthView.getHeight(0) != 3
          || color.isClosed()
          || depth.isClosed()
          || colorView.isClosed()
          || depthView.isClosed()) {
        throw new AssertionError("Fallback main target has unusable attachments");
      }
    } finally {
      rejected = List.of();
      if (target != null) target.destroyBuffers();
    }
    if (color == null
        || depth == null
        || colorView == null
        || depthView == null
        || !color.isClosed()
        || !depth.isClosed()
        || !colorView.isClosed()
        || !depthView.isClosed()) {
      throw new AssertionError("Fallback main target retained attachments after destroy");
    }
  }

  private static void checkRecovery() {
    MainTarget target = new MainTarget(3, 3);
    try {
      assertFormat(
          Objects.requireNonNull(target.getColorTexture()),
          ReleaseAllocationFormat.recoveryFormat());
    } finally {
      target.destroyBuffers();
    }
  }

  private static void assertFormat(GpuTexture texture, String expected) {
    String actual = ReleaseAllocationFormat.actual(texture);
    if (!expected.equals(actual)) {
      throw new AssertionError("Expected " + expected + " main color, got " + actual);
    }
  }

  /** Called only by the test-side GpuDevice mixin at actual texture creation. */
  public static void reject(@Nullable Supplier<String> label, Object format) {
    if (rejected.isEmpty() || label == null || !"Main / Color".equals(label.get())) return;
    String actual = ReleaseAllocationFormat.attempt(format);
    ATTEMPTS.add(actual);
    if (!List.of("rgba32f", "rgba16f", "rgba8").contains(actual)) {
      throw new AssertionError("Unexpected main-target allocation format: " + actual);
    }
    if (rejected.contains(actual)) {
      throw new GpuOutOfMemoryException("CBBG allocation fixture rejected " + actual);
    }
  }

  private static JsonObject settings() {
    try (var reader =
        Files.newBufferedReader(FabricLoader.getInstance().getConfigDir().resolve("cbbg.json"))) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception failure) {
      throw new AssertionError("Cannot read packaged CBBG settings", failure);
    }
  }

  private static FabricClientCommandSource silentSource() {
    return (FabricClientCommandSource)
        Proxy.newProxyInstance(
            FabricClientCommandSource.class.getClassLoader(),
            new Class<?>[] {FabricClientCommandSource.class},
            (proxy, method, args) -> {
              if (method.getName().equals("sendFeedback") || method.getName().equals("sendError")) {
                return null;
              }
              throw new AssertionError("Unexpected command source call: " + method);
            });
  }

  private static void command(
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      String suffix) {
    try {
      if (dispatcher.execute("cbbg " + suffix, source) != 1) {
        throw new AssertionError("Command failed: " + suffix);
      }
    } catch (CommandSyntaxException failure) {
      throw new AssertionError("Command failed: " + suffix, failure);
    }
  }
}
