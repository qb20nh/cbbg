package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Minecraft 26.3 texture operations for the shared dither lifecycle. */
@NullMarked
public final class DitherPresentation {
  private static final DitherPass PASS = new DitherPass();
  private static @Nullable GpuTexture noise;
  private static @Nullable GpuTextureView noiseView;

  private DitherPresentation() {}

  public static GpuTextureView present(GpuTextureView input) {
    TextureTarget rendered = DitherController.render(true, () -> draw(input));
    return rendered == null ? input : Objects.requireNonNull(rendered.getColorTextureView());
  }

  public static @Nullable TextureTarget screenshot(GpuTextureView input) {
    return DitherController.render(false, () -> draw(input));
  }

  static boolean hasOverlay() {
    return Minecraft.getInstance().gui.overlay() != null;
  }

  static void allocate(int size) {
    noise =
        RenderSystem.getDevice()
            .createTexture("CBBG STBN", 5, GpuFormat.RGBA8_UNORM, size, size, 1, 1);
    noiseView = RenderSystem.getDevice().createTextureView(noise);
  }

  static boolean isReady() {
    return noiseView != null;
  }

  static void upload(NativeImage frame) {
    RenderSystem.getDevice()
        .createCommandEncoder()
        .writeToTexture(Objects.requireNonNull(noise), frame);
  }

  private static TextureTarget draw(GpuTextureView input) {
    CbbgConfig settings = CbbgConfig.get();
    Minecraft client = Minecraft.getInstance();
    var screen = client.gui.screen();
    float strength =
        DitherStrength.effective(
            settings.strength(),
            screen != null && !screen.isInGameUi(),
            client.options.getMenuBackgroundBlurriness());
    float coordScale = RenderScaleCompat.getDitherCoordScale();
    return PASS.render(
        input,
        Objects.requireNonNull(noiseView),
        strength,
        coordScale,
        coordScale,
        settings.mode() == CbbgConfig.Mode.DEMO);
  }

  static boolean hasDetectedNoFloatFormats() {
    var color = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
    return CbbgClient.isEnabled() && color != null && color.getFormat() == GpuFormat.RGBA8_UNORM;
  }

  static void close() {
    PASS.close();
    if (noiseView != null) {
      noiseView.close();
      noiseView = null;
    }
    if (noise != null) {
      noise.close();
      noise = null;
    }
  }
}
