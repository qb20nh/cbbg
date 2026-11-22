package com.qb20nh.cbbg.parity;

import com.mojang.blaze3d.platform.GlStateManager;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Exercises the packaged dither pipeline across actual window and world transitions. */
final class ClassicOpenGlLifecycle {
  private enum Stage {
    INITIAL,
    RESIZED,
    FULLSCREEN,
    WINDOWED,
    RELOADED,
    LEFT,
    REJOINED
  }

  private static final int FRAMES = 6;
  private final int width;
  private final int height;
  private Stage stage = Stage.INITIAL;
  private long baseline = ClassicPackagedSmoke.presentationCount();
  private CompletableFuture<Void> reload;

  ClassicOpenGlLifecycle(Minecraft client) {
    width = client.getWindow().getWidth();
    height = client.getWindow().getHeight();
  }

  boolean tick(Minecraft client) throws Exception {
    long current = ClassicPackagedSmoke.presentationCount();
    if (!ready(client)) {
      baseline = current;
      return false;
    }
    long draws = current - baseline;
    if (draws < FRAMES) return false;
    checkTarget(client);
    var window = client.getWindow();
    String name = stage.name().toLowerCase(Locale.ROOT);
    ClassicPackagedSmoke.capture(client, "opengl-lifecycle-" + name + ".png");
    System.out.println(
        "CBBG_CLASSIC_OPENGL_LIFECYCLE stage="
            + name
            + " size="
            + window.getWidth()
            + "x"
            + window.getHeight()
            + " fullscreen="
            + window.isFullscreen()
            + " world="
            + (client.level != null)
            + " draws="
            + draws);
    baseline = ClassicPackagedSmoke.presentationCount();
    switch (stage) {
      case INITIAL -> GLFW.glfwSetWindowSize(window.getWindow(), width - 64, height - 48);
      case RESIZED, FULLSCREEN -> window.toggleFullScreen();
      case WINDOWED -> {
        GLFW.glfwSetWindowSize(window.getWindow(), width, height);
        reload = client.reloadResourcePacks();
      }
      case RELOADED -> {
        client.level.disconnect();
        client.clearLevel(new TitleScreen());
      }
      case LEFT -> ClassicPackagedSmoke.reopenWorld(client, (TitleScreen) client.screen);
      case REJOINED -> {
        return true;
      }
    }
    stage = Stage.values()[stage.ordinal() + 1];
    return false;
  }

  private boolean ready(Minecraft client) {
    var window = client.getWindow();
    long handle = window.getWindow();
    if (stage == Stage.FULLSCREEN) {
      long monitor = GLFW.glfwGetWindowMonitor(handle);
      if (!window.isFullscreen() || monitor == 0L) return false;
      var mode = GLFW.glfwGetVideoMode(monitor);
      if (mode == null
          || window.getWidth() != mode.width()
          || window.getHeight() != mode.height()) {
        return false;
      }
    } else {
      boolean small = stage == Stage.RESIZED || stage == Stage.WINDOWED;
      if (window.isFullscreen()
          || GLFW.glfwGetWindowMonitor(handle) != 0L
          || window.getWidth() != (small ? width - 64 : width)
          || window.getHeight() != (small ? height - 48 : height)) return false;
    }
    if (stage == Stage.RELOADED) {
      if (!reload.isDone() || client.getOverlay() != null) return false;
      reload.join();
    }
    if (stage == Stage.LEFT) {
      return client.level == null
          && client.getSingleplayerServer() == null
          && client.screen instanceof TitleScreen;
    }
    return client.level != null && client.player != null && client.screen == null;
  }

  private static void checkTarget(Minecraft client) {
    var target = client.getMainRenderTarget();
    var window = client.getWindow();
    if (target.width != window.getWidth()
        || target.height != window.getHeight()
        || !GL30.glIsFramebuffer(target.frameBufferId)
        || !GL11.glIsTexture(target.getColorTextureId())) {
      throw new AssertionError("OpenGL lifecycle lost its live window-sized framebuffer");
    }
    ClassicPackagedSmoke.requireFormat(target, GL30.GL_RGBA16F);
    int previous = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
    try {
      if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE
          || GL30.glGetFramebufferAttachmentParameteri(
                  GL30.GL_READ_FRAMEBUFFER,
                  GL30.GL_COLOR_ATTACHMENT0,
                  GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)
              != target.getColorTextureId()) {
        throw new AssertionError("OpenGL lifecycle framebuffer lost its color attachment");
      }
    } finally {
      GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previous);
    }
  }
}
