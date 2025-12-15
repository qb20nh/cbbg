package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.render.CbbgDither;
import com.qb20nh.cbbg.render.CbbgLightTextureHooks;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlCommandEncoder.class)
public abstract class GlCommandEncoderMixin {

  private static final ThreadLocal<Integer> PRESENT_DEPTH = ThreadLocal.withInitial(() -> 0);
  private static final AtomicBoolean loggedOnce = new AtomicBoolean(false);
  private static volatile Boolean lastEnabled = null;

  @Inject(method = "presentTexture", at = @At("HEAD"), cancellable = true)
  private void cbbg$presentTexture(GpuTextureView textureView, CallbackInfo ci) {
    // Prevent recursion when cbbg itself calls presentTexture for its dither output.
    if (PRESENT_DEPTH.get() > 0) {
      return;
    }
    boolean enabledNow = CbbgClient.isEnabled();
    handleToggle(enabledNow);
    if (!enabledNow) {
      return;
    }

    Minecraft mc = Minecraft.getInstance();
    if (mc == null) {
      return;
    }

    // Only intercept presenting the *main* render target.
    GpuTextureView main = mc.getMainRenderTarget().getColorTextureView();
    if (main == null || main != textureView) {
      return;
    }

    logVerificationOnce(main);

    PRESENT_DEPTH.set(PRESENT_DEPTH.get() + 1);
    try {
      if (CbbgDither.blitToScreenWithDither(textureView)) {
        ci.cancel();
      }
    } finally {
      int next = PRESENT_DEPTH.get() - 1;
      if (next <= 0) {
        PRESENT_DEPTH.remove();
      } else {
        PRESENT_DEPTH.set(next);
      }
    }
  }

  private static void handleToggle(boolean enabledNow) {
    Boolean prev = lastEnabled;
    if (prev == null) {
      lastEnabled = enabledNow;
      return;
    }
    if (prev == enabledNow) {
      return;
    }

    lastEnabled = enabledNow;

    // Log verification again after a toggle, and reset any “disabled due to error” state.
    loggedOnce.set(false);
    CbbgDither.resetAfterToggle();

    // Recreate targets on the next frame boundary so we don’t destroy textures mid-present.
    RenderSystem.queueFencedTask(
        () -> {
          Minecraft mc = Minecraft.getInstance();
          if (mc == null) {
            return;
          }

          // Recreate the main target so it matches the current enabled state.
          mc.getMainRenderTarget().resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());

          // Recreate the lightmap texture so it matches the current enabled state.
          try {
            Object lightTex = mc.gameRenderer.lightTexture();
            if (lightTex instanceof CbbgLightTextureHooks hooks) {
              hooks.cbbg$recreateLightTexture(enabledNow);
            }
          } catch (Throwable t) {
            CbbgClient.LOGGER.warn("Failed to recreate lightmap texture after cbbg toggle.", t);
          }
        });
  }

  private static void logVerificationOnce(GpuTextureView mainView) {
    if (!loggedOnce.compareAndSet(false, true)) {
      return;
    }

    try {
      int mainInternal = getTextureInternalFormat(mainView.texture());
      Integer lightmapInternal = null;
      try {
        GpuTextureView lightmap = Minecraft.getInstance().gameRenderer.lightTexture().getTextureView();
        lightmapInternal = lightmap != null ? getTextureInternalFormat(lightmap.texture()) : null;
      } catch (Throwable ignored) {
        // Best-effort; don’t fail rendering if the path changes.
      }

      // Default framebuffer encoding + SRGB conversion state.
      int prevFbo = GlStateManager._getInteger(GL30.GL_FRAMEBUFFER_BINDING);
      int encoding;
      boolean fbSrgb;
      try {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        encoding =
            GL30.glGetFramebufferAttachmentParameteri(
                GL30.GL_FRAMEBUFFER, GL30.GL_BACK_LEFT, GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING);
        fbSrgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
      } finally {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
      }

      CbbgClient.LOGGER.info(
          "cbbg verify: MainTarget internalFormat={} Lightmap internalFormat={} DefaultFB encoding={} FRAMEBUFFER_SRGB={}",
          glInternalName(mainInternal),
          lightmapInternal == null ? "unknown" : glInternalName(lightmapInternal),
          glEncodingName(encoding),
          fbSrgb);
    } catch (Throwable t) {
      CbbgClient.LOGGER.warn("cbbg verify failed (continuing without verification).", t);
    }
  }

  private static int getTextureInternalFormat(GpuTexture texture) {
    // Read GL internal format from the currently allocated texture storage.
    // This is the actual verification that our RGBA16F override is taking effect.
    int prev = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      // This works for the OpenGL backend (which 1.21.11 uses).
      int id = ((com.mojang.blaze3d.opengl.GlTexture) texture).glId();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
      return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
    } finally {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
    }
  }

  private static String glInternalName(int internal) {
    return switch (internal) {
      case GL11.GL_RGBA8 -> "GL_RGBA8(0x8058)";
      case GL30.GL_RGBA16F -> "GL_RGBA16F(0x881A)";
      default -> String.format("0x%04X", internal);
    };
  }

  private static String glEncodingName(int encoding) {
    // GL_SRGB=0x8C40, GL_LINEAR=0x2601
    return switch (encoding) {
      case GL21.GL_SRGB -> "GL_SRGB(0x8C40)";
      case GL11.GL_LINEAR -> "GL_LINEAR(0x2601)";
      default -> String.format("0x%04X", encoding);
    };
  }
}
