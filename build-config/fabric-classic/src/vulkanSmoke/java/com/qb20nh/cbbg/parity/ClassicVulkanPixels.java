package com.qb20nh.cbbg.parity;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdClearAttachments;

import com.mojang.blaze3d.platform.NativeImage;
import com.qb20nh.cbbg.reference.DitherReference;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.vulkanmod.vulkan.Renderer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;

public final class ClassicVulkanPixels {
  private static final int STRIPS = 32;
  private static final String[] MODES = {"demo", "enabled", "disabled"};
  private static int phase;
  private static long presentAfter = -1;
  private static int drawn;

  private ClassicVulkanPixels() {}

  public static void draw() {
    var renderer = Renderer.getInstance();
    var client = Minecraft.getInstance();
    if (client.level == null) return;
    int width = renderer.getSwapChain().getWidth();
    int height = renderer.getSwapChain().getHeight();
    int middle = height / 2;
    renderer.getMainPass().rebindMainTarget();
    try (var stack = MemoryStack.stackPush()) {
      var color = VkClearAttachment.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
      var rect = VkClearRect.calloc(1, stack).layerCount(1);
      for (int strip = 0; strip < STRIPS; strip++) {
        int start = (strip * width + STRIPS - 1) / STRIPS;
        int end = ((strip + 1) * width + STRIPS - 1) / STRIPS;
        clear(color, rect, start, 0, end - start, middle, strip, true);
      }
      clear(color, rect, 0, middle, width, height - middle, 0, false);
    }
    drawn++;
  }

  private static void clear(
      VkClearAttachment.Buffer color,
      VkClearRect.Buffer rect,
      int x,
      int y,
      int width,
      int height,
      int strip,
      boolean ramp) {
    rect.rect().offset().set(x, y);
    rect.rect().extent().set(width, height);
    var rgba = color.clearValue().color();
    for (int channel = 0; channel < 3; channel++) {
      rgba.float32(channel, source(strip, ramp, channel));
    }
    rgba.float32(3, 0.5f);
    vkCmdClearAttachments(Renderer.getCommandBuffer(), color, rect);
  }

  // These multiples of 1/1024 are exactly representable in the RGBA16F scene.
  private static float source(int strip, boolean ramp, int channel) {
    return ramp
        ? (17 + (strip * (31 + channel * 7) + channel * 131) % 992) / 1024f
        : switch (channel) {
          case 0 -> 377 / 1024f;
          case 1 -> 731 / 1024f;
          default -> 89 / 1024f;
        };
  }

  public static boolean check(Minecraft client, long presents) throws Exception {
    if (presentAfter < 0) presentAfter = presents + 12;
    if (presents < presentAfter || drawn < 12) return false;
    String mode = MODES[phase];
    boolean demo = mode.equals("demo");
    boolean enabled = !mode.equals("disabled");
    ClassicVulkanSmoke.checkSceneFormat(enabled ? 97 : 44);
    var directory = client.gameDirectory.toPath();
    try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget());
        var png = Files.newInputStream(directory.resolve(".cbbg/stbn_16x16x8_0.png"));
        var noise = NativeImage.read(png)) {
      if (image.getWidth() != 803
          || image.getHeight() != 601
          || noise.getWidth() != 16
          || noise.getHeight() != 16) {
        throw new AssertionError("Unexpected fixture pixel dimensions");
      }
      image.writeToFile(directory.resolve("vulkan-pixels-" + mode + ".png"));
      compare(image, noise, enabled, demo, mode);
      if (!enabled) image.writeToFile(directory.resolve("vulkan-world.png"));
    }
    if (++phase == MODES.length) return true;
    ClassicPackagedSmoke.command(client, "cbbg mode set " + MODES[phase]);
    presentAfter = presents + 12;
    return false;
  }

  private static void compare(
      NativeImage image, NativeImage noise, boolean enabled, boolean demo, String mode) {
    int width = image.getWidth();
    int height = image.getHeight();
    long errors = 0;
    long topOriginErrors = 0;
    long alphaErrors = 0;
    long changed = 0;
    for (int y = 0; y < height; y++) {
      int ny = DitherReference.noiseCoordinate(height - 1 - y, 1, 16);
      int top = DitherReference.noiseCoordinate(y, 1, 16);
      for (int x = 0; x < width; x++) {
        int pixel = image.getPixelRGBA(x, y);
        if ((pixel >>> 24) != 255) alphaErrors++;
        int nx = DitherReference.noiseCoordinate(x, 1, 16);
        int sample = noise.getPixelRGBA(nx, ny);
        int topSample = noise.getPixelRGBA(nx, top);
        int strip = x * STRIPS / width;
        for (int channel = 0; channel < 3; channel++) {
          int shift = channel * 8;
          int actual = (pixel >>> shift) & 255;
          float value = source(strip, y < height / 2, channel);
          int expected =
              DitherReference.shaderChannel(
                  value, (sample >>> shift) & 255, enabled ? 2f : 0f, x, width, demo);
          int topExpected =
              DitherReference.shaderChannel(
                  value, (topSample >>> shift) & 255, enabled ? 2f : 0f, x, width, demo);
          if (actual != expected) errors++;
          if (actual != topExpected) topOriginErrors++;
          if (actual != DitherReference.shaderChannel(value, 0, 0, x, width, false)) changed++;
        }
      }
    }
    System.out.println(
        "CBBG_CLASSIC_VULKAN_PIXELS mode="
            + mode
            + " channels="
            + (width * height * 3)
            + " mismatches="
            + errors
            + " topOriginMismatches="
            + topOriginErrors
            + " alphaMismatches="
            + alphaErrors
            + " changedChannels="
            + changed);
    if (errors != 0 || alphaErrors != 0 || enabled != (changed > 0)) {
      throw new AssertionError("Vulkan output differs from the deterministic CPU pixels");
    }
  }
}
