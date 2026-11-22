package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.mixin.FrontendGpuSurfaceAccessor;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Direct OpenGL and Vulkan window rendering on Minecraft 26.3. */
@NullMarked
public final class WindowDitherPresentation {
  private static boolean windowPass;
  private static @Nullable GpuSurface vulkanSurface;
  private static int windowDrawBuffer;

  /** Marks encoders whose presentation method applies dithering itself. */
  public interface WindowEncoder {}

  private WindowDitherPresentation() {}

  public static boolean isWindowPass() {
    return windowPass;
  }

  public static int windowDrawBuffer() {
    return windowDrawBuffer;
  }

  public static boolean presentToWindow(GpuTextureView input, int width, int height) {
    RenderSystem.assertOnRenderThread();
    if (!CbbgClient.isEnabled()
        || !DitherPresentation.isReady()
        || input.getWidth(0) != width
        || input.getHeight(0) != height
        || !WindowFramebuffer.supportsDirectDrawing()) return false;
    int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    try {
      GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
      windowDrawBuffer = GL11.glGetInteger(GL11.GL_DRAW_BUFFER);
      windowPass = true;
      return DitherController.render(true, () -> DitherPresentation.draw(input)) != null;
    } finally {
      windowPass = false;
      GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
      GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
      GlStateManager._disableScissorTest();
      GlStateManager._viewport(0, 0, width, height);
      GlStateManager._depthMask(true);
      GlStateManager._colorMask(15);
    }
  }

  public static boolean canPresentToVulkanWindow(GpuSurface surface, GpuTextureView input) {
    return surface.isAcquired()
        && surface instanceof FrontendGpuSurfaceAccessor access
        && access.cbbg$backend() instanceof VulkanWindowPresentation.Surface window
        && window.cbbg$canDraw(input);
  }

  public static void setVulkanSurface(@Nullable GpuSurface surface) {
    vulkanSurface = surface;
  }

  @SuppressWarnings(
      "ReferenceEquality") // Limit the effect to the main surface selected by MinecraftMixin.
  public static boolean presentToVulkanWindow(
      GpuSurface surface, CommandEncoder encoder, GpuTextureView input) {
    if (surface != vulkanSurface || !canPresentToVulkanWindow(surface, input)) return false;
    var window =
        (VulkanWindowPresentation.Surface) ((FrontendGpuSurfaceAccessor) surface).cbbg$backend();
    Boolean rendered =
        DitherController.renderFrame(
            true,
            () -> {
              CbbgConfig settings = CbbgConfig.get();
              Minecraft client = Minecraft.getInstance();
              var screen = client.gui.screen();
              float strength =
                  DitherStrength.effective(
                      settings.strength(),
                      screen != null && !screen.isInGameUi(),
                      client.options.getMenuBackgroundBlurriness());
              double scale = RenderScaleCompat.getDitherScale();
              window.cbbg$draw(
                  encoder,
                  input,
                  DitherPresentation.requireNoiseView(),
                  strength,
                  DitherScale.forDimension(scale, input.getWidth(0)),
                  DitherScale.forDimension(scale, input.getHeight(0)),
                  settings.mode() == CbbgConfig.Mode.DEMO);
              return true;
            });
    return Boolean.TRUE.equals(rendered);
  }
}
