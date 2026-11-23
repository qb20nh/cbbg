package com.qb20nh.cbbg.parity;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

/** Observes VulkanMod on a GLFW no-API window without using OpenGL for the test. */
public final class ClassicVulkanSmoke {
  private static long presentations;
  private static final boolean CHECK_LIFECYCLE = Boolean.getBoolean("cbbg.test.vulkanLifecycle");
  private static final boolean CHECK_SETTINGS = Boolean.getBoolean("cbbg.test.vulkanSettings");
  private static final boolean CHECK_PIXELS = Boolean.getBoolean("cbbg.test.vulkanPixels");
  private static final boolean CHECK_PIPELINE =
      Boolean.getBoolean("cbbg.test.vulkanPipelineRecovery");
  private static final boolean CHECK_SAMPLER =
      Boolean.getBoolean("cbbg.test.vulkanSamplerRecovery");
  private static final boolean CHECK_WARNING = Boolean.getBoolean("cbbg.test.vulkanFormatWarning");
  static long ditherDraws;
  static int noiseWidth;
  private static Object ditherPipeline;
  private static Method boundTexture;
  private static Method drawPixels;
  private static Method checkPixels;
  private int ticks;
  private boolean opened;
  private boolean finished;
  private long joinedAt = -1;
  private ClassicVulkanLifecycle lifecycle;
  private ClassicVulkanSettings settings;
  private ClassicVulkanPipelineFailure pipeline;
  private ClassicVulkanSamplerFailure sampler;
  private ClassicVulkanWarning warning;

  public static void observePresent() {
    presentations++;
  }

  public static void drawPixelFixture() {
    if (!CHECK_PIXELS || drawPixels == null) return;
    try {
      drawPixels.invoke(null);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not populate the deterministic Vulkan scene", failure);
    }
  }

  /** Called only by the fixture agent after the real Vulkan format query. */
  public static void maskFormatFeatures(int format, Object properties) {
    String profile = System.getProperty("cbbg.test.vulkanFormatMask", "");
    if ((format == 109 && (profile.equals("full") || profile.equals("both")))
        || (format == 97 && profile.equals("both"))) {
      try {
        long offset = properties.getClass().getField("OPTIMALTILINGFEATURES").getLong(null);
        long address = ((Pointer) properties).address() + offset;
        int original = MemoryUtil.memGetInt(address);
        int blend = 0x100;
        if ((original & blend) == 0) {
          throw new AssertionError("The test device already lacks blend support for " + format);
        }
        MemoryUtil.memPutInt(address, original & ~blend);
        System.out.println("CBBG_CLASSIC_VULKAN_MASK format=" + format + " feature=blend");
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError("Could not mask the Vulkan test capability", failure);
      }
    }
  }

  public static void observeDither(Object pipeline) {
    if (!CHECK_LIFECYCLE && !CHECK_SETTINGS && !CHECK_PIXELS && !CHECK_PIPELINE && !CHECK_SAMPLER)
      return;
    try {
      if (ditherPipeline == null) {
        if (!"cbbg_vulkanmod_dither".equals(pipeline.getClass().getField("name").get(pipeline))) {
          return;
        }
        ditherPipeline = pipeline;
        boundTexture =
            Class.forName("net.vulkanmod.vulkan.texture.VTextureSelector")
                .getMethod("getBoundTexture", int.class);
      }
      if (pipeline != ditherPipeline) return;
      Object noise = boundTexture.invoke(null, 1);
      noiseWidth = noise.getClass().getField("width").getInt(noise);
      ditherDraws++;
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not observe the bound Vulkan noise texture", failure);
    }
  }

  void tick(Minecraft client) {
    if (finished) return;
    if (++ticks > 1200) throw new AssertionError("Timed out waiting for a presented Vulkan world");
    try {
      if (!opened && client.getOverlay() == null && client.screen instanceof TitleScreen) {
        checkInstallation(client);
        opened = true;
        ClassicPackagedSmoke.createWorld(client);
      } else if (opened
          && client.player != null
          && (client.screen == null || settings != null || warning != null)) {
        if (joinedAt < 0) joinedAt = presentations;
        if (presentations < joinedAt + 12) return;
        if (CHECK_PIXELS) {
          if (checkPixels == null) {
            if (noiseWidth != 16) return;
            var probe = Class.forName("com.qb20nh.cbbg.parity.ClassicVulkanPixels");
            drawPixels = probe.getMethod("draw");
            checkPixels = probe.getMethod("check", Minecraft.class, long.class);
          }
          if (!(boolean) checkPixels.invoke(null, client, presentations)) return;
        } else if (CHECK_SETTINGS) {
          if (settings == null) {
            if (noiseWidth != 64) return;
            settings = new ClassicVulkanSettings(client);
          }
          if (!settings.tick(client, presentations)) return;
        } else if (CHECK_LIFECYCLE) {
          if (lifecycle == null) lifecycle = new ClassicVulkanLifecycle();
          if (!lifecycle.tick(client, presentations)) return;
        } else if (CHECK_PIPELINE) {
          if (pipeline == null) pipeline = new ClassicVulkanPipelineFailure();
          if (!pipeline.tick(client, presentations)) return;
        } else if (CHECK_SAMPLER) {
          if (sampler == null) sampler = new ClassicVulkanSamplerFailure();
          if (!sampler.tick(client, presentations)) return;
        } else if (CHECK_WARNING) {
          if (warning == null) warning = new ClassicVulkanWarning(client, presentations);
          if (!warning.tick(client, presentations)) return;
        } else {
          boolean reference = Boolean.getBoolean("cbbg.test.reference");
          boolean disabled = Boolean.getBoolean("cbbg.test.disabled");
          if (!reference) {
            checkSceneFormat(Integer.getInteger("cbbg.test.vulkanSceneFormat", disabled ? 44 : 97));
          }
          capture(
              client,
              "vulkan-world.png",
              !reference && !disabled && !Boolean.getBoolean("cbbg.test.vulkanOriginalTarget"));
        }
        System.out.println("CBBG_CLASSIC_VULKAN_WORLD presentations=" + (presentations - joinedAt));
        finished = true;
        client.stop();
      }
    } catch (Exception failure) {
      throw new IllegalStateException("VulkanMod world fixture failed", failure);
    }
  }

  static void capture(Minecraft client, String name, boolean divider) throws Exception {
    try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
      if (image.getWidth() != client.getWindow().getWidth()
          || image.getHeight() != client.getWindow().getHeight()) {
        throw new AssertionError("Vulkan screenshot dimensions differ from the window");
      }
      checkDivider(image, divider);
      image.writeToFile(client.gameDirectory.toPath().resolve(name));
    }
  }

  static void checkSceneFormat(int expected) throws ReflectiveOperationException {
    var renderer = Class.forName("net.vulkanmod.vulkan.Renderer");
    var instance = renderer.getMethod("getInstance").invoke(null);
    var mainPass = renderer.getMethod("getMainPass").invoke(instance);
    var pass = Class.forName("net.vulkanmod.vulkan.pass.MainPass");
    int id = (int) pass.getMethod("getColorAttachmentGlId").invoke(mainPass);
    var textureClass = Class.forName("net.vulkanmod.gl.GlTexture");
    var texture = textureClass.getMethod("getTexture", int.class).invoke(null, id);
    var image = textureClass.getMethod("getVulkanImage").invoke(texture);
    int format = image.getClass().getField("format").getInt(image);
    if (format != expected && !(expected == 44 && format == 37)) {
      throw new AssertionError(
          "Unexpected Vulkan scene format " + format + ", expected " + expected);
    }
    System.out.println(
        "CBBG_CLASSIC_VULKAN_SCENE format=" + format + " disabled=" + (expected == 44));
  }

  private static void checkDivider(
      com.mojang.blaze3d.platform.NativeImage image, boolean expectDivider) {
    int middle = image.getWidth() / 2;
    int matches = 0;
    int rows = image.getHeight() / 4;
    for (int y = 0; y < rows; y++) {
      int left = image.getPixelRGBA(middle - 1, y);
      int center = image.getPixelRGBA(middle, y);
      if (Math.abs((left & 255) + (center & 255) - 255) <= 2
          && Math.abs((left >> 8 & 255) + (center >> 8 & 255) - 255) <= 2
          && Math.abs((left >> 16 & 255) + (center >> 16 & 255) - 255) <= 2) {
        matches++;
      }
    }
    if (expectDivider ? matches < rows * 0.8 : matches > rows * 0.2) {
      throw new AssertionError("Unexpected Vulkan demo divider coverage: " + matches + "/" + rows);
    }
  }

  private static void checkInstallation(Minecraft client) throws Exception {
    var loader = FabricLoader.getInstance();
    var vulkan = loader.getModContainer("vulkanmod").orElseThrow();
    boolean reference = Boolean.getBoolean("cbbg.test.reference");
    if (loader.isModLoaded("cbbg") == reference) {
      throw new AssertionError("Unexpected CBBG presence in VulkanMod fixture");
    }
    if (!reference) {
      var cbbg = loader.getModContainer("cbbg").orElseThrow();
      var paths = cbbg.getOrigin().getPaths();
      if (paths.size() != 1
          || !Files.isSameFile(paths.get(0), Path.of(System.getProperty("cbbg.test.jar")))) {
        throw new AssertionError("Wrong packaged CBBG artifact in VulkanMod fixture");
      }
    }
    long window = client.getWindow().getWindow();
    if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_CLIENT_API) != GLFW.GLFW_NO_API) {
      throw new AssertionError("VulkanMod unexpectedly created an OpenGL context");
    }
    Object device =
        Class.forName("net.vulkanmod.vulkan.Vulkan").getMethod("getDevice").invoke(null);
    Class<?> type = device.getClass();
    System.out.println(
        "CBBG_CLASSIC_VULKAN_DEVICE mod="
            + vulkan.getMetadata().getVersion().getFriendlyString()
            + " device="
            + type.getField("deviceName").get(device)
            + " api="
            + type.getField("vkVersion").get(device)
            + " glfw=no-api reference="
            + reference);
  }
}
