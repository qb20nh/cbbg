package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.stbn.StbnTextureManager;
import java.util.Objects;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Minecraft 26.1 texture operations for the shared dither lifecycle. */
@NullMarked
public final class DitherPresentation {
  private static final DitherPass PASS = new DitherPass();
  private static final StbnTextureManager NOISE = new StbnTextureManager();

  private DitherPresentation() {}

  public static GpuTextureView present(GpuTextureView input) {
    TextureTarget rendered = DitherController.render(true, () -> draw(input));
    return rendered == null ? input : Objects.requireNonNull(rendered.getColorTextureView());
  }

  public static boolean presentToWindow(GpuTextureView input, Supplier<RenderPass> destination) {
    if (!CbbgClient.isEnabled()) return false;
    var loader = FabricLoader.getInstance();
    var window = Minecraft.getInstance().getWindow();
    if (loader.isModLoaded("iris")
        || loader.isModLoaded("immediatelyfast")
        || input.getWidth(0) != window.getWidth()
        || input.getHeight(0) != window.getHeight()
        || !WindowFramebuffer.supportsDirectDrawing()) {
      return false;
    }
    return DitherController.renderFrame(
            true,
            () -> {
              draw(input, destination);
              return Boolean.TRUE;
            })
        != null;
  }

  public static @Nullable TextureTarget screenshot(GpuTextureView input) {
    return DitherController.render(false, () -> draw(input));
  }

  static boolean hasOverlay() {
    return Minecraft.getInstance().getOverlay() != null;
  }

  static void allocate(int size) {
    NOISE.ensureTexture(size, size);
  }

  static boolean isReady() {
    return NOISE.isReady();
  }

  static void upload(NativeImage frame) {
    NOISE.upload(frame);
  }

  private static TextureTarget draw(GpuTextureView input) {
    return Objects.requireNonNull(draw(input, null));
  }

  private static @Nullable TextureTarget draw(
      GpuTextureView input, @Nullable Supplier<RenderPass> destination) {
    CbbgConfig settings = CbbgConfig.get();
    Minecraft client = Minecraft.getInstance();
    var screen = client.screen;
    float strength =
        DitherStrength.effective(
            settings.strength(),
            screen != null && !screen.isInGameUi(),
            client.options.getMenuBackgroundBlurriness());
    double coordScale = RenderScaleCompat.getDitherScale();
    if (destination != null) {
      PASS.renderInto(
          input,
          Objects.requireNonNull(NOISE.getView()),
          strength,
          DitherScale.forDimension(coordScale, input.getWidth(0)),
          DitherScale.forDimension(coordScale, input.getHeight(0)),
          settings.mode() == CbbgConfig.Mode.DEMO,
          destination);
      return null;
    }
    return PASS.render(
        input,
        Objects.requireNonNull(NOISE.getView()),
        strength,
        DitherScale.forDimension(coordScale, input.getWidth(0)),
        DitherScale.forDimension(coordScale, input.getHeight(0)),
        settings.mode() == CbbgConfig.Mode.DEMO);
  }

  static boolean hasDetectedNoFloatFormats() {
    var color = Minecraft.getInstance().getMainRenderTarget().getColorTexture();
    return CbbgClient.isEnabled()
        && color != null
        && FloatAttachments.internalFormat(color) == org.lwjgl.opengl.GL11.GL_RGBA8;
  }

  static void close() {
    try (NOISE) {
      PASS.close();
    }
  }
}
