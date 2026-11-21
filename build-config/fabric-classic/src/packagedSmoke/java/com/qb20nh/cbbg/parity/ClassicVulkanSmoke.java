package com.qb20nh.cbbg.parity;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;

/** Observes VulkanMod on a GLFW no-API window without using OpenGL for the test. */
public final class ClassicVulkanSmoke {
  private static long presentations;
  private int ticks;
  private boolean opened;
  private boolean finished;
  private long joinedAt = -1;

  public static void observePresent() {
    presentations++;
  }

  void tick(Minecraft client) {
    if (finished) return;
    if (++ticks > 1200) throw new AssertionError("Timed out waiting for a presented Vulkan world");
    try {
      if (!opened && client.getOverlay() == null && client.screen instanceof TitleScreen) {
        checkInstallation(client);
        opened = true;
        ClassicPackagedSmoke.createWorld(client);
      } else if (opened && client.player != null && client.screen == null) {
        if (joinedAt < 0) joinedAt = presentations;
        if (presentations < joinedAt + 12) return;
        try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
          if (image.getWidth() != client.getWindow().getWidth()
              || image.getHeight() != client.getWindow().getHeight()) {
            throw new AssertionError("Vulkan screenshot dimensions differ from the window");
          }
          image.writeToFile(client.gameDirectory.toPath().resolve("vulkan-world.png"));
        }
        System.out.println("CBBG_CLASSIC_VULKAN_WORLD presentations=" + (presentations - joinedAt));
        finished = true;
        client.stop();
      }
    } catch (Exception failure) {
      throw new IllegalStateException("VulkanMod world fixture failed", failure);
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
