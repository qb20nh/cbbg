package com.qb20nh.cbbg.parity;

import com.qb20nh.cbbg.gametest.IrisFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Exercises the packaged mod while Iris toggles and reloads the shared shader fixture. */
final class ClassicIrisSmoke {
  private enum Phase {
    INACTIVE,
    ACTIVE,
    RELOADED,
    RECOVERED
  }

  private final boolean restart;
  private final boolean restoreAfterReload = Boolean.getBoolean("cbbg.test.irisCycle");
  private Phase phase;
  private int phaseStarted;
  private long baseline;
  private boolean captureRequested;
  private byte[] savedConfig;

  ClassicIrisSmoke(boolean restart, int ticks, long presentations) {
    this.restart = restart;
    phase = restart ? Phase.ACTIVE : Phase.INACTIVE;
    phaseStarted = ticks;
    baseline = restart ? 0 : presentations;
  }

  boolean tick(Minecraft client, int ticks, long presentations) throws Exception {
    if (ticks < phaseStarted + 8) return false;
    Path config = client.gameDirectory.toPath().resolve("config/cbbg.json");
    if (restoreAfterReload
        && savedConfig != null
        && !Arrays.equals(savedConfig, Files.readAllBytes(config))) {
      throw new AssertionError("Iris changed the saved CBBG configuration");
    }
    switch (phase) {
      case INACTIVE -> {
        if (presentations < baseline + 10) return false;
        requireActive(false);
        ClassicPackagedSmoke.requireFormat(client, GL30.GL_RGBA16F);
        capture(client, "iris-inactive.png");
        if (restoreAfterReload) savedConfig = Files.readAllBytes(config);
        // The screenshot also invokes the dither shader while Iris is inactive.
        baseline = ClassicPackagedSmoke.presentationCount();
        IrisFixture.installPack();
        setShaders(true);
        phase = Phase.ACTIVE;
      }
      case ACTIVE -> {
        if (!checkSuppressed(
            client, presentations, restart ? "iris-restart-active.png" : "iris-active.png"))
          return false;
        if (restart) {
          setShaders(false);
          phase = Phase.RECOVERED;
        } else {
          IrisFixture.saveAndReload();
          phase = Phase.RELOADED;
        }
      }
      case RELOADED -> {
        if (!checkSuppressed(client, presentations, "iris-reloaded.png")) return false;
        System.out.println("CBBG_CLASSIC_IRIS_RELOAD_PASS");
        if (!restoreAfterReload) return true;
        setShaders(false);
        phase = Phase.RECOVERED;
      }
      case RECOVERED -> {
        if (presentations < baseline + 10) return false;
        requireActive(false);
        ClassicPackagedSmoke.requireFormat(client, GL30.GL_RGBA16F);
        capture(client, "iris-restored.png");
        System.out.println(
            (restart ? "CBBG_CLASSIC_IRIS_RESTART_PASS" : "CBBG_CLASSIC_IRIS_CYCLE_PASS")
                + " presentations="
                + (presentations - baseline));
        return true;
      }
    }
    phaseStarted = ticks;
    return false;
  }

  private boolean checkSuppressed(Minecraft client, long presentations, String filename)
      throws Exception {
    requireActive(true);
    if (presentations != baseline)
      throw new AssertionError(
          "Dither draws while Iris shaders were active: " + (presentations - baseline));
    ClassicPackagedSmoke.requireFormat(client, GL11.GL_RGBA8);
    if (!captureRequested) {
      ClassicWindowCapture.request(client, filename);
      captureRequested = true;
      return false;
    }
    if (ClassicWindowCapture.isPending()) return false;
    captureRequested = false;
    System.out.println("CBBG_CLASSIC_IRIS_SUPPRESSED " + filename + " format=RGBA8 draws=0");
    return true;
  }

  private static void requireActive(boolean expected) throws ReflectiveOperationException {
    Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
    Object instance = api.getMethod("getInstance").invoke(null);
    if (!Boolean.valueOf(expected).equals(api.getMethod("isShaderPackInUse").invoke(instance))) {
      throw new AssertionError("Unexpected active Iris shader state, expected " + expected);
    }
  }

  private static void setShaders(boolean enable) {
    Object config = Objects.requireNonNull(IrisFixture.iris("getIrisConfig"));
    if (enable) IrisFixture.invoke(config, "setShaderPackName", String.class, "cbbg-parity");
    IrisFixture.invoke(config, "setShadersEnabled", boolean.class, enable);
    IrisFixture.saveAndReload();
  }

  private static void capture(Minecraft client, String name) throws Exception {
    try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
      image.writeToFile(Path.of(client.gameDirectory.getPath(), name));
    }
  }
}
