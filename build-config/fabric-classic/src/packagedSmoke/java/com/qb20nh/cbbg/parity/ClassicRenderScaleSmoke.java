package com.qb20nh.cbbg.parity;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Checks RenderScale's live texture and CBBG's screenshot noise grid. */
final class ClassicRenderScaleSmoke {
  private record Profile(String precision, boolean active, boolean forceLinear, int format) {}

  private static final float[] SCALES = {0.5f, 1f, 2f};
  private static final Profile[] PROFILES = {
    new Profile("rgba16f", true, false, GL30.GL_RGBA16F),
    new Profile("rgba16f", true, true, GL30.GL_RGBA16F),
    new Profile("rgba32f", true, false, GL30.GL_RGBA32F),
    new Profile("rgba16f", false, false, GL11.GL_RGBA8)
  };
  private final Object renderer;
  private final Object config;
  private final Field scaleField;
  private final Field linearField;
  private final Field worldTarget;
  private final Method resolutionChanged;
  private final float savedScale;
  private final boolean savedLinear;
  private int currentCase;
  private int started;
  private long initialPresentations;

  ClassicRenderScaleSmoke(Minecraft client, int ticks) throws Exception {
    Class<?> type = Class.forName("dev.zelo.renderscale.RenderScale");
    renderer = type.getMethod("getInstance").invoke(null);
    config = type.getMethod("getConfig").invoke(null);
    worldTarget = type.getField("renderTarget");
    resolutionChanged = type.getMethod("onResolutionChanged");
    scaleField = config.getClass().getField("scale");
    linearField = config.getClass().getField("forceLinear");
    savedScale = scaleField.getFloat(config);
    savedLinear = linearField.getBoolean(config);
    configure(client, ticks);
  }

  boolean tick(Minecraft client, int ticks) throws Exception {
    Profile profile = PROFILES[currentCase % PROFILES.length];
    float scale = SCALES[currentCase / PROFILES.length];
    if (ticks < started + 5) return false;
    long presentations = ClassicPackagedSmoke.presentationCount() - initialPresentations;
    if (profile.active() && presentations < 3) return false;
    if (!profile.active() && presentations != 0) {
      throw new AssertionError("CBBG dither ran while disabled at RenderScale " + scale);
    }

    RenderTarget main = client.getMainRenderTarget();
    RenderTarget world = (RenderTarget) worldTarget.get(renderer);
    if (world == null
        || world == main
        || world.width != (int) (main.width * scale)
        || world.height != (int) (main.height * scale)) {
      throw new AssertionError("RenderScale world target has wrong size or ownership at " + scale);
    }
    ClassicPackagedSmoke.requireFormat(main, profile.format());
    ClassicPackagedSmoke.requireFormat(world, profile.format());
    boolean linear = profile.forceLinear() || scale > 1;
    checkFilter(world, linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
    int block = profile.active() ? (scale < 1 ? 2 : 1) : 0;
    if (profile.active()) checkGrid(client, main, block);
    System.out.println(
        "CBBG_CLASSIC_RENDERSCALE_CASE scale="
            + scale
            + " mode="
            + (profile.active() ? "enabled" : "disabled")
            + " format="
            + profile.format()
            + " filter="
            + (linear ? "linear" : "nearest")
            + " world="
            + world.width
            + "x"
            + world.height
            + " block="
            + block);
    currentCase++;
    if (currentCase == SCALES.length * PROFILES.length) {
      scaleField.setFloat(config, savedScale);
      linearField.setBoolean(config, savedLinear);
      resolutionChanged.invoke(renderer);
      System.out.println("CBBG_CLASSIC_RENDERSCALE_PASS cases=" + currentCase);
      return true;
    }
    configure(client, ticks);
    return false;
  }

  private void configure(Minecraft client, int ticks) throws Exception {
    Profile profile = PROFILES[currentCase % PROFILES.length];
    ClassicPackagedSmoke.command(client, "cbbg format set " + profile.precision());
    ClassicPackagedSmoke.command(
        client, "cbbg mode set " + (profile.active() ? "enabled" : "disabled"));
    scaleField.setFloat(config, SCALES[currentCase / PROFILES.length]);
    linearField.setBoolean(config, profile.forceLinear());
    resolutionChanged.invoke(renderer);
    initialPresentations = ClassicPackagedSmoke.presentationCount();
    started = ticks;
  }

  private static void checkFilter(RenderTarget target, int expected) {
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    GlStateManager._bindTexture(target.getColorTextureId());
    try {
      if (GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER) != expected
          || GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER) != expected) {
        throw new AssertionError("RenderScale filter differs; expected " + expected);
      }
    } finally {
      GlStateManager._bindTexture(previous);
    }
  }

  private void checkGrid(Minecraft client, RenderTarget main, int block) throws Exception {
    main.bindWrite(true);
    GlStateManager._disableScissorTest();
    GlStateManager._colorMask(true, true, true, true);
    GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[] {0.5f, 0.5f, 0.5f, 1});
    try (NativeImage image = Screenshot.takeScreenshot(main)) {
      int period = 16 * block; // The fixture uses a 16x16 noise tile.
      int origin = 64;
      int distinct = 0;
      int withinPair = 0;
      for (int y = origin; y < origin + period; y++) {
        for (int x = origin; x < origin + period; x++) {
          int pixel = image.getPixelRGBA(x, y);
          if (pixel != image.getPixelRGBA(x + period, y)
              || pixel != image.getPixelRGBA(x, y + period)) {
            throw new AssertionError(
                "Dither did not repeat at the expected scaled tile period " + period);
          }
          if (pixel != image.getPixelRGBA(x - x % 2, y - y % 2)) withinPair++;
          if (pixel != image.getPixelRGBA(origin, origin)) distinct++;
        }
      }
      if (distinct == 0 || (block == 2 ? withinPair != 0 : withinPair == 0)) {
        throw new AssertionError(
            "Dither noise pixels do not form the expected " + block + "x" + block + " grid");
      }
      image.writeToFile(client.gameDirectory.toPath().resolve("grid-" + currentCase + ".png"));
    }
  }
}
