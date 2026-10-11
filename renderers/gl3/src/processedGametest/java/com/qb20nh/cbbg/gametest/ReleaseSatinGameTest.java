package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Exercises Satin 2.0.0's managed post-processing against packaged 1.21.1. */
@NullMarked
public final class ReleaseSatinGameTest implements FabricClientGameTest {
  private static final String API = "org.ladysnake.satin.api.managed.";

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    String version =
        FabricLoader.getInstance()
            .getModContainer("satin")
            .orElseThrow(() -> new AssertionError("Satin fixture requires Satin 2.0.0"))
            .getMetadata()
            .getVersion()
            .getFriendlyString();
    if (!version.equals("2.0.0")) throw new AssertionError("Unexpected Satin version: " + version);
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      ReleaseLifecycleGameTest.command(context, "mode set disabled");
      ReleaseLifecycleGameTest.command(context, "stbn size 16");
      ReleaseLifecycleGameTest.command(context, "stbn depth 8");
      ReleaseLifecycleGameTest.command(context, "stbn seed 42");
      ReleaseLifecycleGameTest.command(context, "stbn generate");
      ReleaseLifecycleGameTest.command(context, "format set rgba16f");
      for (boolean enabled : new boolean[] {false, true}) exercise(context, enabled);
    }
  }

  private static void exercise(ClientGameTestContext context, boolean enabled) {
    String mode = enabled ? "enabled" : "disabled";
    ReleaseLifecycleGameTest.command(context, "mode set " + mode);
    ReleaseLifecycleGameTest.awaitFormat(context, enabled ? GL30.GL_RGBA16F : GL11.GL_RGBA8);
    if (enabled) ReleaseLifecycleGameTest.awaitPresentation(context);
    Object manager =
        context.computeOnClient(
            c -> Objects.requireNonNull(invoke("ShaderEffectManager", null, "getInstance")));
    Object effect =
        context.computeOnClient(
            c ->
                Objects.requireNonNull(
                    invoke(
                        "ShaderEffectManager",
                        manager,
                        "manage",
                        new Class<?>[] {ResourceLocation.class},
                        ResourceLocation.fromNamespaceAndPath(
                            "cbbg", "shaders/post/satinfixture.json"))));
    int[] original =
        context.computeOnClient(
            c ->
                new int[] {
                  ReleaseViewport.windowWidth(c.getWindow()),
                  ReleaseViewport.windowHeight(c.getWindow()),
                  c.getMainRenderTarget().width,
                  c.getMainRenderTarget().height
                });
    try {
      context.runOnClient(c -> invoke("ManagedShaderEffect", effect, "initialize"));
      context.runOnClient(c -> check(c, effect, mode + "-initial"));
      int width = original[0] == 854 ? 912 : 854;
      int height = original[1] == 480 ? 512 : 480;
      ReleaseViewport.resizeWindow(context, width, height);
      context.waitFor(
          c -> {
            RenderTarget swap = target(effect);
            return swap.width == c.getMainRenderTarget().width
                && swap.height == c.getMainRenderTarget().height
                && (swap.width != original[2] || swap.height != original[3])
                && ReleaseViewport.windowWidth(c.getWindow()) == width
                && ReleaseViewport.windowHeight(c.getWindow()) == height;
          },
          600);
      context.runOnClient(c -> check(c, effect, mode + "-resized"));
      RenderTarget old = context.computeOnClient(c -> target(effect));
      var reload = context.computeOnClient(c -> c.reloadResourcePacks());
      context.waitFor(c -> reload.isDone() && c.getOverlay() == null, 600);
      reload.join();
      context.runOnClient(
          c -> {
            // Check the old object because GL may reuse its numeric texture ID during reload.
            if (old.getColorTextureId() > 0 || old.frameBufferId >= 0)
              throw new AssertionError("Satin resource reload retained its old target");
            check(c, effect, mode + "-reloaded");
          });
      if (enabled) {
        ReleaseLifecycleGameTest.awaitPresentation(context);
        ReleasePixels.check(context, false);
      }
    } finally {
      context.runOnClient(
          c -> {
            RenderTarget swap = target(effect);
            int texture = swap.getColorTextureId();
            int framebuffer = swap.frameBufferId;
            RenderTarget output = ReleaseObservations.output();
            invoke(
                "ShaderEffectManager",
                manager,
                "dispose",
                new Class<?>[] {apiClass("ManagedShaderEffect")},
                effect);
            if (GL11.glIsTexture(texture) || GL30.glIsFramebuffer(framebuffer))
              throw new AssertionError("Disposing Satin leaked its framebuffer or texture");
            live(c.getMainRenderTarget());
            if (enabled) live(Objects.requireNonNull(output));
            c.getMainRenderTarget().bindWrite(true);
            noErrors("Satin disposal (" + mode + ")");
          });
      ReleaseViewport.resizeWindow(context, original[0], original[1]);
      context.waitFor(
          c ->
              ReleaseViewport.windowWidth(c.getWindow()) == original[0]
                  && ReleaseViewport.windowHeight(c.getWindow()) == original[1]
                  && c.getMainRenderTarget().width == original[2]
                  && c.getMainRenderTarget().height == original[3],
          600);
    }
  }

  private static RenderTarget target(Object effect) {
    Object managed =
        invoke(
            "ManagedShaderEffect",
            effect,
            "getTarget",
            new Class<?>[] {String.class},
            "satinfixture");
    return (RenderTarget)
        Objects.requireNonNull(
            invoke("ManagedFramebuffer", Objects.requireNonNull(managed), "getFramebuffer"));
  }

  private static void check(Minecraft client, Object effect, String phase) {
    noErrors("before " + phase);
    RenderTarget main = client.getMainRenderTarget();
    RenderTarget swap = target(effect);
    live(main);
    live(swap);
    if (swap.getColorTextureId() == main.getColorTextureId()
        || swap.frameBufferId == main.frameBufferId
        || swap.width != main.width
        || swap.height != main.height
        || ReleaseLifecycleGameTest.format(swap.getColorTextureId()) != GL11.GL_RGBA8)
      throw new AssertionError("Satin ownership, dimensions or RGBA8 format failed: " + phase);
    RenderTarget output = ReleaseObservations.output();
    if (output != null && swap.getColorTextureId() == output.getColorTextureId())
      throw new AssertionError("Satin aliased CBBG's presentation target: " + phase);
    int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int[] packing = {
      GL11.GL_PACK_ROW_LENGTH,
      GL11.GL_PACK_SKIP_ROWS,
      GL11.GL_PACK_SKIP_PIXELS,
      GL11.GL_UNPACK_ROW_LENGTH,
      GL11.GL_UNPACK_SKIP_ROWS,
      GL11.GL_UNPACK_SKIP_PIXELS
    };
    int[] saved = new int[packing.length];
    for (int i = 0; i < packing.length; i++) {
      saved[i] = GL11.glGetInteger(packing[i]);
      GL11.glPixelStorei(packing[i], 0);
    }
    try {
      ByteBuffer source = BufferUtils.createByteBuffer(main.width * main.height * 4);
      for (int y = 0; y < main.height; y++) {
        for (int x = 0; x < main.width; x++) {
          source.put((byte) (x < main.width / 2 ? 32 : 192));
          source.put((byte) (y < main.height / 2 ? 64 : 224));
          source.put((byte) 128).put((byte) 255);
        }
      }
      source.flip();
      GlStateManager._bindTexture(main.getColorTextureId());
      GL11.glTexSubImage2D(
          GL11.GL_TEXTURE_2D,
          0,
          0,
          0,
          main.width,
          main.height,
          GL11.GL_RGBA,
          GL11.GL_UNSIGNED_BYTE,
          source);
      invoke("ManagedShaderEffect", effect, "render", new Class<?>[] {float.class}, 0f);
      pixels(swap, source, phase + " intermediate");
      pixels(main, source, phase + " main");
      try (var image = Screenshot.takeScreenshot(main)) {
        Path evidence = Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
        Files.createDirectories(evidence);
        image.writeToFile(evidence.resolve("satin-" + phase + ".png"));
      } catch (java.io.IOException failure) {
        throw new AssertionError("Cannot retain Satin evidence: " + phase, failure);
      }
      noErrors(phase);
    } finally {
      GlStateManager._bindTexture(binding);
      for (int i = 0; i < packing.length; i++) GL11.glPixelStorei(packing[i], saved[i]);
      main.bindWrite(true);
    }
  }

  private static void pixels(RenderTarget target, ByteBuffer expected, String phase) {
    GlStateManager._bindTexture(target.getColorTextureId());
    ByteBuffer actual = BufferUtils.createByteBuffer(expected.limit());
    GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, actual);
    for (int i = 0; i < expected.limit(); i++) {
      if (Byte.toUnsignedInt(actual.get(i)) != Byte.toUnsignedInt(expected.get(i)))
        throw new AssertionError("Satin pixel mismatch: " + phase + " at byte " + i);
    }
  }

  private static void live(RenderTarget target) {
    if (!GL11.glIsTexture(target.getColorTextureId())
        || !GL30.glIsFramebuffer(target.frameBufferId))
      throw new AssertionError("Satin lifecycle destroyed a live framebuffer or texture");
    target.bindWrite(true);
    target.checkStatus();
  }

  private static void noErrors(String phase) {
    int error = GL11.glGetError();
    if (error != GL11.GL_NO_ERROR)
      throw new AssertionError("OpenGL error " + error + " during " + phase);
  }

  private static Class<?> apiClass(String name) {
    try {
      return Class.forName(API + name);
    } catch (ClassNotFoundException failure) {
      throw new AssertionError("Satin 2.0.0 API unavailable: " + API + name, failure);
    }
  }

  private static @Nullable Object invoke(String owner, @Nullable Object receiver, String name) {
    return invoke(owner, receiver, name, new Class<?>[0]);
  }

  private static @Nullable Object invoke(
      String owner, @Nullable Object receiver, String name, Class<?>[] types, Object... args) {
    try {
      return apiClass(owner).getMethod(name, types).invoke(receiver, args);
    } catch (InvocationTargetException failure) {
      throw new AssertionError("Satin " + owner + "." + name + " failed", failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Satin 2.0.0 API mismatch: " + owner + "." + name, failure);
    }
  }
}
