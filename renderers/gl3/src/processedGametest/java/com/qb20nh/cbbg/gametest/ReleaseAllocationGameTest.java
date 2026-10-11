package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.qb20nh.cbbg.api.DitherOptions;
import com.qb20nh.cbbg.api.NoiseVolume;
import com.qb20nh.cbbg.render.DitherPass;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

/** Incomplete GL attachments exercise the packaged full-size fallback chain. */
@NullMarked
public final class ReleaseAllocationGameTest implements FabricClientGameTest {
  private static @Nullable RenderTarget owned;
  private static List<Integer> rejected = List.of();
  private static final List<Integer> attempts = new ArrayList<>();
  private static boolean mainScope;
  private static boolean auxiliaryScope;
  private static int disposals;
  private static @Nullable RenderTarget failedTarget;
  private static boolean allocationReady;
  private static boolean utilityScope;
  private static int failedColor;
  private static int failedDepth;
  private static int failedFramebuffer;

  @SuppressWarnings("ReferenceEquality") // Track the exact target whose GL resources are tested.
  public static void allocating(RenderTarget target, boolean ready) {
    if ((mainScope && target instanceof MainTarget)
        || (auxiliaryScope && !(target instanceof MainTarget))
        || target == owned) {
      owned = target;
      allocationReady = ready;
    }
  }

  public static boolean reject(int format) {
    RenderTarget target = owned;
    if (!allocationReady
        || target == null
        || GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != target.getColorTextureId())
      return false;
    attempts.add(format);
    if (format == GL11.GL_RGBA8 && rejected.contains(format) && (mainScope || auxiliaryScope))
      failed(target);
    return rejected.contains(format);
  }

  @SuppressWarnings("ReferenceEquality") // Other targets can be destroyed during the same frame.
  public static void disposed(RenderTarget target, int color, int depth, int framebuffer) {
    if (target != owned) return;
    if (GL11.glIsTexture(color) || GL11.glIsTexture(depth) || GL30.glIsFramebuffer(framebuffer))
      throw new AssertionError("Fixture-owned target destruction retained GL attachments");
    disposals++;
  }

  @SuppressWarnings("ReferenceEquality") // Fail only the utility pass's newly allocated target.
  public static void checking(RenderTarget target) {
    if (!utilityScope || !allocationReady || target != owned) return;
    failed(target);
    throw new IllegalStateException("Utility target initialization failure");
  }

  @SuppressWarnings("ReferenceEquality") // Capture attachments belonging to the selected instance.
  private static void failed(RenderTarget target) {
    if ((!mainScope && !auxiliaryScope) || target != owned) return;
    failedTarget = target;
    failedColor = target.getColorTextureId();
    failedDepth = target.getDepthTextureId();
    failedFramebuffer = target.frameBufferId;
    if (failedColor <= 0
        || (target.useDepth && failedDepth <= 0)
        || failedFramebuffer < 0
        || !GL11.glIsTexture(failedColor)
        || (failedDepth > 0 && !GL11.glIsTexture(failedDepth))
        || !GL30.glIsFramebuffer(failedFramebuffer))
      throw new AssertionError("Exhausted failure did not own live vanilla attachments");
  }

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    Boolean synchronous =
        context.computeOnClient(
            client -> {
              var capabilities = GL.getCapabilities();
              if (!capabilities.OpenGL43
                  && !capabilities.GL_KHR_debug
                  && !capabilities.GL_ARB_debug_output) return null;
              boolean original = GL11.glIsEnabled(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
              // Finish framebuffer diagnostics before the test thread returns.
              GL11.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
              return original;
            });
    try {
      runAllocations(context);
    } finally {
      context.runOnClient(
          client -> {
            if (synchronous != null) {
              GL11.glFinish();
              if (synchronous) GL11.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
              else GL11.glDisable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
              if (GL11.glIsEnabled(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS) != synchronous)
                throw new AssertionError("Allocation test did not restore GL debug output state");
            }
          });
    }
  }

  private static void runAllocations(ClientGameTestContext context) {
    context.runOnClient(client -> checkUtilityInitializationFailure());
    context.runOnClient(
        client -> {
          // Auxiliary allocations must not poison the session's main-format capability policy.
          var target = new TextureTarget(64, 48, true, Minecraft.ON_OSX);
          int color = target.getColorTextureId();
          int depth = target.getDepthTextureId();
          int framebuffer = target.frameBufferId;
          owned = target;
          allocationReady = true;
          try {
            check(target, List.of(), List.of(GL30.GL_RGBA32F), GL30.GL_RGBA32F);
            check(
                target,
                List.of(GL30.GL_RGBA32F),
                List.of(GL30.GL_RGBA32F, GL30.GL_RGBA16F),
                GL30.GL_RGBA16F);
            check(
                target,
                List.of(GL30.GL_RGBA32F, GL30.GL_RGBA16F),
                List.of(GL30.GL_RGBA32F, GL30.GL_RGBA16F, GL11.GL_RGBA8),
                GL11.GL_RGBA8);
            rejected = List.of(GL30.GL_RGBA32F, GL30.GL_RGBA16F, GL11.GL_RGBA8);
            attempts.clear();
            try {
              allocate(target);
              throw new AssertionError("Exhausted allocation succeeded");
            } catch (IllegalStateException expected) {
              if (!Objects.equals(expected.getMessage(), "Could not allocate RGBA8 render target"))
                throw new AssertionError("Unexpected allocation failure", expected);
            }
            if (!attempts.equals(List.of(GL30.GL_RGBA32F, GL30.GL_RGBA16F, GL11.GL_RGBA8)))
              throw new AssertionError("Exhausted fallback order changed: " + attempts);
            check(target, List.of(), List.of(GL30.GL_RGBA32F), GL30.GL_RGBA32F);
            if (target.getColorTextureId() != color
                || target.getDepthTextureId() != depth
                || target.frameBufferId != framebuffer)
              throw new AssertionError("Fallback replaced a caller-owned color/depth attachment");
          } finally {
            rejected = List.of();
            owned = null;
            target.destroyBuffers();
          }
          if (GL11.glIsTexture(color)
              || GL11.glIsTexture(depth)
              || GL30.glIsFramebuffer(framebuffer))
            throw new AssertionError("Caller-owned failed/recovered target was not disposed");
        });
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      context.runOnClient(client -> checkMain());
    }
  }

  private static void checkUtilityInitializationFailure() {
    var input = UtilitiesBackend.input(4, 4);
    try (var noise = UtilitiesBackend.noise(NoiseVolume.generate(2, 2, 1, 0), 0);
        var pass = new DitherPass()) {
      var options = new DitherOptions(1, 1, 1, false);
      for (int attempt = 0; attempt < 2; attempt++) {
        utilityScope = true;
        auxiliaryScope = true;
        try {
          try {
            pass.render(input, noise.texture(), options);
            throw new AssertionError("Utility initialization failure was not applied");
          } catch (IllegalStateException expected) {
            if (!Objects.equals(expected.getMessage(), "Utility target initialization failure"))
              throw new AssertionError("Unexpected utility initialization failure", expected);
          }
          if (failedTarget == null
              || GL11.glIsTexture(failedColor)
              || GL30.glIsFramebuffer(failedFramebuffer))
            throw new AssertionError("Failed utility initialization retained GL resources");
          if (!GL11.glIsTexture(input.getColorTextureId()) || !GL11.glIsTexture(noise.texture()))
            throw new AssertionError("Failed utility initialization destroyed input textures");
        } finally {
          utilityScope = false;
          auxiliaryScope = false;
          RenderTarget failed = failedTarget;
          if (failed != null
              && (GL11.glIsTexture(failedColor) || GL30.glIsFramebuffer(failedFramebuffer)))
            failed.destroyBuffers();
          owned = null;
          failedTarget = null;
        }
      }
      var output = pass.render(input, noise.texture(), options);
      ReleaseUtilityCalls.checkClosed(pass, output, input, noise);
    } finally {
      input.destroyBuffers();
    }
  }

  private static void checkMain() {
    String originalMode;
    String originalFormat;
    var settings = ReleaseWorldPixelsGameTest.currentSettings();
    originalMode = settings.get("mode").getAsString().toLowerCase(java.util.Locale.ROOT);
    originalFormat = settings.get("pixelFormat").getAsString().toLowerCase(java.util.Locale.ROOT);
    Set<Object> tested = capabilities("tested");
    Set<Object> unsupported = capabilities("unsupported");
    Set<Object> originalTested = new HashSet<>(tested);
    Set<Object> originalUnsupported = new HashSet<>(unsupported);
    Object originalScope = scopedFormat();
    try {
      scopedFormat(null);
      command("mode set enabled");
      command("format set rgba32f");
      for (List<Integer> reject :
          List.of(
              List.<Integer>of(),
              List.of(GL30.GL_RGBA32F),
              List.of(GL30.GL_RGBA32F, GL30.GL_RGBA16F))) {
        tested.clear();
        tested.addAll(originalTested);
        unsupported.clear();
        unsupported.addAll(originalUnsupported);
        rejected = reject;
        attempts.clear();
        mainScope = true;
        MainTarget target = new MainTarget(64, 48);
        try {
          int format =
              reject.isEmpty()
                  ? GL30.GL_RGBA32F
                  : reject.size() == 1 ? GL30.GL_RGBA16F : GL11.GL_RGBA8;
          List<Integer> expected =
              reject.isEmpty()
                  ? List.of(GL30.GL_RGBA32F)
                  : reject.size() == 1
                      ? List.of(GL30.GL_RGBA32F, GL30.GL_RGBA16F)
                      : List.of(GL30.GL_RGBA32F, GL30.GL_RGBA16F, GL11.GL_RGBA8);
          if (!attempts.equals(expected))
            throw new AssertionError("Main constructor fallback order changed: " + attempts);
          assertTarget(target, format, 64, 48);
          if (!reject.isEmpty() && unsupported.size() != originalUnsupported.size() + reject.size())
            throw new AssertionError(
                "Main allocation failures did not persist as session rejections");
          int before = disposals;
          rejected = List.of();
          attempts.clear();
          target.resize(80, 60, Minecraft.ON_OSX);
          if (disposals != before + 1)
            throw new AssertionError("Main resize did not dispose old attachments");
          assertTarget(target, format, 80, 60);
          List<Integer> resizeAttempts = format == GL11.GL_RGBA8 ? List.of() : List.of(format);
          if (!attempts.equals(resizeAttempts))
            throw new AssertionError("Main resize retried session-rejected formats: " + attempts);
        } finally {
          target.destroyBuffers();
          owned = null;
          mainScope = false;
        }
      }
      tested.clear();
      tested.addAll(originalTested);
      unsupported.clear();
      unsupported.addAll(originalUnsupported);
      checkExhaustedMain();
      tested.clear();
      tested.addAll(originalTested);
      unsupported.clear();
      unsupported.addAll(originalUnsupported);
      checkExhaustedResize();
      tested.clear();
      tested.addAll(originalTested);
      unsupported.clear();
      unsupported.addAll(originalUnsupported);
      checkExhaustedAuxiliary();
      rejected = List.of();
      mainScope = true;
      MainTarget recovered = new MainTarget(64, 48);
      try {
        assertTarget(recovered, GL30.GL_RGBA32F, 64, 48);
      } finally {
        recovered.destroyBuffers();
        owned = null;
        mainScope = false;
      }
    } finally {
      rejected = List.of();
      mainScope = false;
      auxiliaryScope = false;
      owned = null;
      tested.clear();
      tested.addAll(originalTested);
      unsupported.clear();
      unsupported.addAll(originalUnsupported);
      try {
        command("format set " + originalFormat);
        command("mode set " + originalMode);
      } finally {
        scopedFormat(originalScope);
      }
    }
  }

  private static void checkExhaustedMain() {
    rejected = List.of(GL30.GL_RGBA32F, GL30.GL_RGBA16F, GL11.GL_RGBA8);
    attempts.clear();
    mainScope = true;
    failedTarget = null;
    int before = disposals;
    MainTarget unexpected = null;
    try {
      try {
        unexpected = new MainTarget(64, 48);
        throw new AssertionError("Exhausted main construction succeeded");
      } catch (IllegalStateException expected) {
        assertCleanFailure(expected, before + 1);
        RenderTarget failed = Objects.requireNonNull(failedTarget);
        Set<?> tracked =
            (Set<?>)
                Objects.requireNonNull(
                    ReleasePackagedFields.get(
                        "com.qb20nh.cbbg.render.MainTargets", "java.util.Set TARGETS"));
        if (tracked.contains(failed))
          throw new AssertionError("Failed main instance entered MainTargets tracking");
      }
    } finally {
      if (unexpected != null) unexpected.destroyBuffers();
      // Clean up only after checking production disposal, including on regression failure.
      RenderTarget failed = failedTarget;
      if (failed != null
          && (GL11.glIsTexture(failedColor)
              || GL11.glIsTexture(failedDepth)
              || GL30.glIsFramebuffer(failedFramebuffer))) failed.destroyBuffers();
      rejected = List.of();
      mainScope = false;
      owned = null;
      failedTarget = null;
    }
  }

  @SuppressWarnings("ReferenceEquality") // Resize must retain the same target instance.
  private static void checkExhaustedResize() {
    rejected = List.of();
    mainScope = true;
    MainTarget target = new MainTarget(64, 48);
    int before = disposals;
    attempts.clear();
    failedTarget = null;
    rejected = List.of(GL30.GL_RGBA32F, GL30.GL_RGBA16F, GL11.GL_RGBA8);
    int unsupportedBefore = capabilities("unsupported").size();
    try {
      try {
        target.resize(80, 60, Minecraft.ON_OSX);
        throw new AssertionError("Exhausted main resize succeeded");
      } catch (IllegalStateException expected) {
        // Resize first destroys old attachments, then cleans up its failed new allocation.
        assertCleanFailure(expected, before + 2);
        if (failedTarget != target || capabilities("unsupported").size() != unsupportedBefore + 2)
          throw new AssertionError(
              "Failed main resize lost ownership or persistent format rejection");
      }
      rejected = List.of();
      attempts.clear();
      target.resize(64, 48, Minecraft.ON_OSX);
      assertTarget(target, GL11.GL_RGBA8, 64, 48);
      if (!attempts.isEmpty())
        throw new AssertionError("Recovery resize retried session-rejected formats");
    } finally {
      target.destroyBuffers();
      rejected = List.of();
      mainScope = false;
      owned = null;
      failedTarget = null;
    }
  }

  private static void checkExhaustedAuxiliary() {
    Object originalScope = scopedFormat();
    int unsupportedBefore = capabilities("unsupported").size();
    try {
      scopedFormat(rgba32f());
      for (boolean depth : new boolean[] {true, false}) {
        rejected = List.of(GL30.GL_RGBA32F, GL30.GL_RGBA16F, GL11.GL_RGBA8);
        attempts.clear();
        auxiliaryScope = true;
        failedTarget = null;
        int before = disposals;
        TextureTarget unexpected = null;
        try {
          try {
            unexpected = new TextureTarget(64, 48, depth, Minecraft.ON_OSX);
            throw new AssertionError("Exhausted auxiliary construction succeeded");
          } catch (IllegalStateException expected) {
            assertCleanFailure(expected, before + 1);
            if (Objects.requireNonNull(failedTarget).useDepth != depth)
              throw new AssertionError("Wrong auxiliary depth ownership in failure fixture");
            if (capabilities("unsupported").size() != unsupportedBefore)
              throw new AssertionError("Auxiliary failure changed main session capabilities");
          }
        } finally {
          if (unexpected != null) unexpected.destroyBuffers();
          RenderTarget failed = failedTarget;
          if (failed != null
              && (GL11.glIsTexture(failedColor)
                  || GL11.glIsTexture(failedDepth)
                  || GL30.glIsFramebuffer(failedFramebuffer))) failed.destroyBuffers();
          rejected = List.of();
          auxiliaryScope = false;
          owned = null;
          failedTarget = null;
        }
        auxiliaryScope = true;
        TextureTarget recovered = new TextureTarget(64, 48, depth, Minecraft.ON_OSX);
        try {
          assertTarget(recovered, GL30.GL_RGBA32F, 64, 48);
        } finally {
          recovered.destroyBuffers();
          auxiliaryScope = false;
          owned = null;
        }
      }
    } finally {
      rejected = List.of();
      auxiliaryScope = false;
      owned = null;
      scopedFormat(originalScope);
    }
  }

  private static void assertCleanFailure(IllegalStateException expected, int expectedDisposals) {
    if (!Objects.equals(expected.getMessage(), "Could not allocate RGBA8 render target"))
      throw new AssertionError("Allocation hook changed the original failure", expected);
    RenderTarget failed = Objects.requireNonNull(failedTarget);
    if (!attempts.equals(rejected))
      throw new AssertionError("Exhausted fallback order changed: " + attempts);
    if (disposals != expectedDisposals
        || GL11.glIsTexture(failedColor)
        || GL11.glIsTexture(failedDepth)
        || GL30.glIsFramebuffer(failedFramebuffer)
        || failed.getColorTextureId() > 0
        || failed.getDepthTextureId() > 0
        || failed.frameBufferId >= 0)
      throw new AssertionError("Failed allocation retained vanilla attachments");
  }

  private static @Nullable Object scopedFormat() {
    String owner = "com.qb20nh.cbbg.render.MenuBlurGuard";
    try {
      return Class.forName(ReleaseMapping.className(owner))
          .getMethod(
              ReleaseMapping.memberName(
                  owner, "com.qb20nh.cbbg.config.CbbgConfig$PixelFormat getActiveFormat()"))
          .invoke(null);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot inspect packaged scoped format", failure);
    }
  }

  private static void scopedFormat(@Nullable Object format) {
    String owner = "com.qb20nh.cbbg.render.MenuBlurGuard";
    try {
      Class.forName(ReleaseMapping.className(owner))
          .getMethod(
              ReleaseMapping.memberName(
                  owner, "void set(com.qb20nh.cbbg.config.CbbgConfig$PixelFormat)"),
              Class.forName(
                  ReleaseMapping.className("com.qb20nh.cbbg.config.CbbgConfig$PixelFormat")))
          .invoke(null, new Object[] {format});
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot restore packaged scoped format", failure);
    }
  }

  private static Object rgba32f() {
    String owner = "com.qb20nh.cbbg.config.CbbgConfig$PixelFormat";
    try {
      return Objects.requireNonNull(
          Class.forName(ReleaseMapping.className(owner))
              .getField(ReleaseMapping.memberName(owner, owner + " RGBA32F"))
              .get(null));
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot inspect packaged pixel formats", failure);
    }
  }

  private static void assertTarget(RenderTarget target, int format, int width, int height) {
    if (target.width != width
        || target.height != height
        || (target.useDepth && !GL11.glIsTexture(target.getDepthTextureId())))
      throw new AssertionError("Main fallback lost full-size color/depth attachments");
    int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    try {
      GlStateManager._bindTexture(target.getColorTextureId());
      if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)
          != format) throw new AssertionError("Wrong actual main fallback format");
      GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
      target.checkStatus();
    } finally {
      GlStateManager._bindTexture(texture);
      GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
      GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
    }
  }

  @SuppressWarnings("unchecked")
  private static Set<Object> capabilities(String member) {
    return (Set<Object>)
        Objects.requireNonNull(
            ReleasePackagedFields.get(
                "com.qb20nh.cbbg.render.MainTargetFormatSupport", "java.util.Set " + member));
  }

  private static void command(String command) {
    ReleaseGenerationGameTest.command(
        Objects.requireNonNull(ReleaseCommands.getActiveDispatcher()),
        ReleaseGenerationGameTest.silentSource(),
        command);
  }

  private static void check(
      RenderTarget target, List<Integer> reject, List<Integer> order, int format) {
    rejected = reject;
    attempts.clear();
    int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    float[] clear = new float[4];
    GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clear);
    boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    var mask = org.lwjgl.BufferUtils.createByteBuffer(4);
    GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
    allocate(target);
    if (GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != texture
        || GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) != read
        || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != draw)
      throw new AssertionError("Allocation fallback leaked GL bindings");
    if (!attempts.equals(order)) throw new AssertionError("Fallback order changed: " + attempts);
    try {
      GlStateManager._bindTexture(target.getColorTextureId());
      if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)
              != format
          || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != 64
          || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) != 48)
        throw new AssertionError("Fallback changed dimensions or actual format");
      GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
      target.checkStatus();
      GlStateManager._disableScissorTest();
      GlStateManager._colorMask(true, true, true, true);
      GL11.glClearColor(0.25f, 0.5f, 0.75f, 1f);
      GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
      float[] pixel = new float[4];
      GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
      for (int i = 0; i < pixel.length; i++) {
        float expected = new float[] {0.25f, 0.5f, 0.75f, 1f}[i];
        if (Math.abs(pixel[i] - expected) > 1f / 255)
          throw new AssertionError("Fallback target cannot clear/read pixels");
      }
    } finally {
      GL11.glClearColor(clear[0], clear[1], clear[2], clear[3]);
      if (scissor) GlStateManager._enableScissorTest();
      else GlStateManager._disableScissorTest();
      GlStateManager._colorMask(
          mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
      GlStateManager._bindTexture(texture);
      GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
      GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
    }
  }

  private static void allocate(RenderTarget target) {
    String owner = "com.qb20nh.cbbg.render.MainTargetFormatSupport";
    String formatOwner = "com.qb20nh.cbbg.config.CbbgConfig$PixelFormat";
    try {
      Class<?> format = Class.forName(ReleaseMapping.className(formatOwner));
      Object requested = rgba32f();
      Class.forName(ReleaseMapping.className(owner))
          .getMethod(
              ReleaseMapping.memberName(
                  owner,
                  "void allocate(com.mojang.blaze3d.pipeline.RenderTarget,com.qb20nh.cbbg.config.CbbgConfig$PixelFormat,boolean)"),
              RenderTarget.class,
              format,
              boolean.class)
          .invoke(null, target, Objects.requireNonNull(requested), false);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof IllegalStateException expected) throw expected;
      throw new AssertionError("Packaged allocation API threw", failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged allocation API changed", failure);
    }
  }
}
