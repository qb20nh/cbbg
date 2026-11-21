package com.qb20nh.cbbg.parity;

import static com.qb20nh.cbbg.parity.ClassicPackagedSmoke.command;
import static com.qb20nh.cbbg.parity.ClassicVulkanSmoke.capture;
import static com.qb20nh.cbbg.parity.ClassicVulkanSmoke.checkSceneFormat;

import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

final class ClassicVulkanLifecycle {
  private enum Phase {
    INITIAL,
    DISABLED,
    PRECISION,
    REGENERATED,
    RESTORED,
    RESIZED,
    RELOADED
  }

  private Phase phase = Phase.INITIAL;
  private long waitUntil;
  private long disabledDraws;
  private CompletableFuture<Void> reload;
  private int width;
  private int height;

  boolean tick(Minecraft client, long presents) throws Exception {
    if (presents < waitUntil) return false;
    switch (phase) {
      case INITIAL -> {
        if (ClassicVulkanSmoke.noiseWidth != 16) return false;
        checkSceneFormat(97); // RGBA16F
        capture(client, "vulkan-demo-before.png", true);
        disabledDraws = ClassicVulkanSmoke.ditherDraws;
        command(client, "cbbg mode set disabled");
        phase = Phase.DISABLED;
      }
      case DISABLED -> {
        checkSceneFormat(44); // VulkanMod's BGRA8 swapchain on this driver
        if (ClassicVulkanSmoke.ditherDraws != disabledDraws) {
          throw new AssertionError("A dither pass ran after CBBG was disabled");
        }
        capture(client, "vulkan-disabled.png", false);
        command(client, "cbbg format set rgba32f");
        command(client, "cbbg mode set demo");
        command(client, "cbbg stbn size 32");
        phase = Phase.PRECISION;
      }
      case PRECISION -> {
        checkSceneFormat(109); // RGBA32F
        if (ClassicVulkanSmoke.noiseWidth != 16) {
          throw new AssertionError("Editing the noise size replaced the texture before generation");
        }
        capture(client, "vulkan-rgba32f.png", true);
        command(client, "cbbg stbn generate");
        phase = Phase.REGENERATED;
      }
      case REGENERATED -> {
        if (ClassicVulkanSmoke.noiseWidth != 32) return false;
        var manifest = client.gameDirectory.toPath().resolve(".cbbg/stbn_32x32x8.sha256");
        if (!Files.isRegularFile(manifest)) throw new AssertionError("New noise cache is missing");
        System.out.println("CBBG_CLASSIC_VULKAN_NOISE sampled=32 pending-edit=16");
        command(client, "cbbg format set rgba16f");
        phase = Phase.RESTORED;
      }
      case RESTORED -> {
        checkSceneFormat(97);
        width = client.getWindow().getWidth() - 64;
        height = client.getWindow().getHeight() - 48;
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), width, height);
        phase = Phase.RESIZED;
      }
      case RESIZED -> {
        if (client.getWindow().getWidth() != width || client.getWindow().getHeight() != height) {
          return false;
        }
        checkSceneFormat(Integer.getInteger("cbbg.test.vulkanResizedFormat", 97));
        capture(client, "vulkan-resized.png", true);
        reload = client.reloadResourcePacks();
        phase = Phase.RELOADED;
      }
      case RELOADED -> {
        if (!reload.isDone() || client.getOverlay() != null) return false;
        reload.join();
        checkSceneFormat(Integer.getInteger("cbbg.test.vulkanResizedFormat", 97));
        if (ClassicVulkanSmoke.noiseWidth != 32) {
          throw new AssertionError("Resource reload lost the 32-pixel noise texture");
        }
        capture(client, "vulkan-world.png", true);
        System.out.println("CBBG_CLASSIC_VULKAN_LIFECYCLE width=" + width + " height=" + height);
        return true;
      }
    }
    waitUntil = presents + 12;
    return false;
  }
}
