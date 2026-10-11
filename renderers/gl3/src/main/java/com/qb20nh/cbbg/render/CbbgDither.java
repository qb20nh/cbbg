package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.api.DitherOptions;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.JigsawBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen;
import org.jspecify.annotations.Nullable;

/** Settings facade; noise ownership and frame advancement live in DitherController. */
public final class CbbgDither {
  private static final DitherPass PASS = new DitherPass();

  private CbbgDither() {}

  public static void resetAfterToggle() {
    DitherController.resetAfterToggle();
  }

  public static void reloadStbn(boolean force) {
    DitherController.reloadStbn(force);
  }

  public static void close() {
    DitherController.close();
  }

  public static boolean isDisabled() {
    return DitherController.isDisabled();
  }

  public static int getStbnFrames() {
    return DitherController.getStbnFrames();
  }

  public static int getCurrentStbnFrameIndex() {
    return DitherController.getCurrentStbnFrameIndex();
  }

  static void closeGpu() {
    PASS.close();
  }

  public static @Nullable TextureTarget renderDitheredTarget(RenderTarget input) {
    return render(input, false, true);
  }

  public static @Nullable TextureTarget renderDemoTarget(RenderTarget input) {
    return render(input, true, true);
  }

  public static @Nullable TextureTarget renderScreenshotTarget(RenderTarget input) {
    return render(input, CbbgClient.isDemoMode(), false);
  }

  private static @Nullable TextureTarget render(RenderTarget input, boolean demo, boolean advance) {
    return render(input, demo, advance, false);
  }

  private static @Nullable TextureTarget render(
      RenderTarget input, boolean demo, boolean advance, boolean present) {
    if (CbbgShaders.get(demo) == null || input.width <= 0 || input.height <= 0) return null;
    return DitherController.render(
        advance,
        () -> {
          var mc = Minecraft.getInstance();
          float strength =
              DitherStrength.effective(
                  CbbgConfig.get().strength(),
                  isMenuScreen(mc.screen),
                  mc.options.getMenuBackgroundBlurriness());
          double scale = RenderScaleCompat.getDitherScale();
          TextureTarget output =
              PASS.render(
                  input,
                  DitherPresentation.texture(),
                  new DitherOptions(
                      strength,
                      coordinateScale(scale, input.width),
                      coordinateScale(scale, input.height),
                      demo));
          if (present) {
            var window = mc.getWindow();
            output.blitToScreen(window.getWidth(), window.getHeight());
          }
          return output;
        });
  }

  // RenderScale 1.21.1 rounds its internal viewport dimensions up.
  private static float coordinateScale(double scale, int size) {
    if (!(scale > 0) || scale >= 1) return 1;
    return (float) Math.max((int) Math.ceil(size * scale), 1) / size;
  }

  // Backport Screen.isInGameUi(): menu screens blur, while interactive world UI does not.
  private static boolean isMenuScreen(@Nullable Screen screen) {
    return screen != null
        && !(screen instanceof AbstractContainerScreen<?>)
        && !(screen instanceof AbstractCommandBlockEditScreen)
        && !(screen instanceof AbstractSignEditScreen)
        && !(screen instanceof BookEditScreen)
        && !(screen instanceof BookViewScreen)
        && !(screen instanceof JigsawBlockEditScreen)
        && !(screen instanceof StructureBlockEditScreen);
  }

  public static boolean blitToScreenWithDither(RenderTarget input) {
    return present(input, CbbgConfig.Mode.ENABLED);
  }

  public static boolean blitToScreenWithDemo(RenderTarget input) {
    return present(input, CbbgConfig.Mode.DEMO);
  }

  private static boolean present(RenderTarget input, CbbgConfig.Mode mode) {
    if (CbbgClient.getEffectiveMode() != mode) return false;
    // Keep the blit inside the controller's failure boundary and lifecycle reset handling.
    return render(input, mode == CbbgConfig.Mode.DEMO, true, true) != null;
  }
}
