package com.qb20nh.cbbg.parity;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Tests Satin's real managed framebuffer and post-processing pipeline against the packaged mod. */
final class ClassicSatinSmoke {
  private enum Phase {
    RELOAD,
    SMALLER,
    RESTORED
  }

  private final Class<?> managerType =
      Class.forName("ladysnake.satin.api.managed.ShaderEffectManager");
  private final Class<?> effectType =
      Class.forName("ladysnake.satin.api.managed.ManagedShaderEffect");
  private final Class<?> framebufferType =
      Class.forName("ladysnake.satin.api.managed.ManagedFramebuffer");
  private final Object manager;
  private final Object effect;
  private final Object managedFloat;
  private final Object managedEight;
  private final Object originalChain;
  private final RenderTarget originalFloat;
  private final RenderTarget originalEight;
  private final int width;
  private final int height;
  private final CompletableFuture<Void> reload;
  private Phase phase = Phase.RELOAD;
  private int checks;

  ClassicSatinSmoke(Minecraft client) throws Exception {
    checkFreshReadableDepth(client);
    manager = managerType.getMethod("getInstance").invoke(null);
    effect =
        managerType
            .getMethod("manage", ResourceLocation.class)
            .invoke(manager, new ResourceLocation("cbbg", "shaders/post/satin_parity.json"));
    effectType.getMethod("initialize").invoke(effect);
    managedFloat = effectType.getMethod("getTarget", String.class).invoke(effect, "float");
    managedEight = effectType.getMethod("getTarget", String.class).invoke(effect, "eight");
    originalChain = effectType.getMethod("getShaderEffect").invoke(effect);
    originalFloat = target(managedFloat);
    originalEight = target(managedEight);
    width = client.getWindow().getWidth();
    height = client.getWindow().getHeight();
    check(client, "initial");
    reload = client.reloadResourcePacks();
  }

  boolean tick(Minecraft client) throws Exception {
    switch (phase) {
      case RELOAD -> {
        if (!reload.isDone() || client.getOverlay() != null) return false;
        reload.join();
        if (effectType.getMethod("getShaderEffect").invoke(effect) == originalChain
            || target(managedFloat) == originalFloat
            || target(managedEight) == originalEight
            || originalFloat.frameBufferId != -1
            || originalEight.frameBufferId != -1) {
          throw new AssertionError("Satin reload did not replace and release both managed targets");
        }
        check(client, "reloaded");
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), width - 64, height - 48);
        phase = Phase.SMALLER;
      }
      case SMALLER -> {
        if (!hasSize(client, width - 64, height - 48)) return false;
        check(client, "smaller");
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), width, height);
        phase = Phase.RESTORED;
      }
      case RESTORED -> {
        if (!hasSize(client, width, height)) return false;
        check(client, "restored");
        RenderTarget floating = target(managedFloat);
        RenderTarget eight = target(managedEight);
        managerType.getMethod("dispose", effectType).invoke(manager, effect);
        if (floating.frameBufferId != -1 || eight.frameBufferId != -1) {
          throw new AssertionError("Satin disposal leaked managed framebuffers");
        }
        System.out.println("CBBG_CLASSIC_SATIN_PASS cases=" + checks + " readableDepth=3");
        return true;
      }
    }
    return false;
  }

  private static boolean hasSize(Minecraft client, int width, int height) {
    return client.getWindow().getWidth() == width && client.getWindow().getHeight() == height;
  }

  private RenderTarget target(Object managed) throws ReflectiveOperationException {
    return (RenderTarget) framebufferType.getMethod("getFramebuffer").invoke(managed);
  }

  private void check(Minecraft client, String label) throws Exception {
    RenderTarget main = client.getMainRenderTarget();
    RenderTarget floating = target(managedFloat);
    RenderTarget eight = target(managedEight);
    if (!hasSize(client, main.width, main.height)
        || floating.width != main.width
        || floating.height != main.height
        || eight.width != main.width
        || eight.height != main.height) {
      throw new AssertionError("Satin managed framebuffer missed a window resize: " + label);
    }
    try {
      for (String format : new String[] {"rgba16f", "rgba32f"}) {
        ClassicPackagedSmoke.command(client, "cbbg format set " + format);
        int activeFormat = format.equals("rgba16f") ? GL30.GL_RGBA16F : GL30.GL_RGBA32F;
        for (String mode : new String[] {"disabled", "enabled", "demo"}) {
          ClassicPackagedSmoke.command(client, "cbbg mode set " + mode);
          ClassicPackagedSmoke.requireFormat(
              main, mode.equals("disabled") ? GL11.GL_RGBA8 : activeFormat);
          ClassicPackagedSmoke.requireFormat(floating, GL30.GL_RGBA16F);
          ClassicPackagedSmoke.requireFormat(eight, GL11.GL_RGBA8);
          checkInversion(main, floating, eight, label + "/" + mode + "/" + format);
          checks++;
        }
      }
    } finally {
      ClassicPackagedSmoke.command(client, "cbbg format set rgba16f");
      ClassicPackagedSmoke.command(client, "cbbg mode set demo");
      main.bindWrite(true);
    }
    System.out.println(
        "CBBG_CLASSIC_SATIN_STAGE "
            + label
            + " "
            + main.width
            + "x"
            + main.height
            + " cases="
            + checks);
  }

  private void checkInversion(
      RenderTarget main, RenderTarget floating, RenderTarget eight, String label)
      throws ReflectiveOperationException {
    main.bindWrite(true);
    GlStateManager._disableScissorTest();
    GlStateManager._colorMask(true, true, true, true);
    GL30.glClearBufferfv(
        GL11.GL_COLOR, 0, new float[] {0.75f + 1f / 1024, 0.5f + 3f / 1024, 0.25f + 5f / 1024, 1});
    float[] source = pixels(main);
    long draws = ClassicPackagedSmoke.presentationCount();
    effectType.getMethod("render", float.class).invoke(effect, 0f);
    if (draws != ClassicPackagedSmoke.presentationCount()) {
      throw new AssertionError("Satin ran CBBG presentation inside post-processing: " + label);
    }
    float[] intermediate = pixels(floating);
    float[] quantized = pixels(eight);
    float[] output = pixels(main);
    for (int i = 0; i < source.length; i++) {
      if (i % 4 == 3) continue;
      float inverse = 1f - source[i];
      int expected = Math.round(intermediate[i] * 255);
      if (Math.abs(intermediate[i] - inverse) > 1f / 2048
          || Math.round(quantized[i] * 255) != expected
          || Math.round(output[i] * 255) != expected) {
        throw new AssertionError(
            "Satin post-processing lost color or precision: " + label + " at " + i);
      }
    }
  }

  private static float[] pixels(RenderTarget target) {
    int previous = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
    try {
      int x = target.width - 1;
      int y = target.height - 1;
      int[][] points = {{0, 0}, {x, 0}, {0, y}, {x, y}, {x / 2, y / 2}};
      float[] result = new float[points.length * 4];
      float[] pixel = new float[4];
      for (int i = 0; i < points.length; i++) {
        GL11.glReadPixels(points[i][0], points[i][1], 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
        System.arraycopy(pixel, 0, result, i * 4, 4);
      }
      return result;
    } finally {
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previous);
    }
  }

  private static void checkFreshReadableDepth(Minecraft client)
      throws ReflectiveOperationException {
    MainTarget target = new MainTarget(32, 24);
    var getter = target.getClass().getMethod("getStillDepthMap");
    var freeze = target.getClass().getMethod("freezeDepthMap");
    int depth = -1;
    try {
      for (int i = 0; i < 2; i++) {
        if (i == 1) target.resize(19, 11, false);
        depth = (int) getter.invoke(target);
        if (!GL11.glIsTexture(depth))
          throw new AssertionError("Satin main target lost readable depth");
        freeze.invoke(target);
        if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)
                != target.width
            || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
                != target.height
            || GL11.glGetError() != GL11.GL_NO_ERROR) {
          throw new AssertionError("Satin main-target depth copy failed");
        }
      }
    } finally {
      target.destroyBuffers();
      client.getMainRenderTarget().bindWrite(true);
    }
    if (GL11.glIsTexture(depth)) throw new AssertionError("Satin leaked readable depth storage");
  }
}
