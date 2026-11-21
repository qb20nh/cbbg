package com.qb20nh.cbbg.gametest;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Reads the actual acquired window image and compares it with the screenshot dither pass. */
@NullMarked
public final class ReleaseVulkanWindowPixels {
  private static @Nullable Request pending;
  private static int sequence;
  private static long draws;

  public static void recordDraw() {
    draws++;
  }

  private static com.google.gson.JsonObject settings() {
    try (var reader =
        Files.newBufferedReader(
            net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir()
                .resolve("cbbg.json"))) {
      return com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  private ReleaseVulkanWindowPixels() {}

  public static void check(ClientGameTestContext context) {
    if (!"vulkan".equals(System.getProperty("cbbg.test.backend"))) return;
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      String previous = settings().get("mode").getAsString();
      float originalStrength = settings().get("strength").getAsFloat();
      int width = context.computeOnClient(client -> client.getWindow().getWidth());
      int height = context.computeOnClient(client -> client.getWindow().getHeight());
      try {
        for (float strength : new float[] {1f, 2f}) {
          context.runOnClient(client -> setStrength(strength));
          for (String mode : new String[] {"enabled", "demo"}) {
            ReleasePixelGameTest.command(context, "mode set " + mode);
            context.waitFor(
                client -> mode.equalsIgnoreCase(settings().get("mode").getAsString()), 600);
            for (int iteration = 0; iteration < 3; iteration++) {
              if (iteration == 1) resize(context, 853, 479);
              if (iteration == 2) resize(context, width, height);
              context.waitTicks(5);
              Request request =
                  context.computeOnClient(
                      client -> {
                        var created = new Request(draws);
                        pending = created;
                        return created;
                      });
              context.waitFor(client -> request.actual.isDone() && request.expected.isDone(), 600);
              int[] actual = request.actual.join();
              try (NativeImage expected = request.expected.join()) {
                compare(actual, expected, mode, sequence++);
              }
            }
          }
        }
      } finally {
        context.runOnClient(
            client -> {
              pending = null;
              setStrength(originalStrength);
            });
        resize(context, width, height);
        ReleasePixelGameTest.command(
            context, "mode set " + previous.toLowerCase(java.util.Locale.ROOT));
        context.waitFor(client -> previous.equals(settings().get("mode").getAsString()), 600);
      }
    }
  }

  private static void setStrength(float strength) {
    try {
      String owner = "com.qb20nh.cbbg.config.CbbgConfig";
      Class.forName(ReleaseMapping.className(owner))
          .getMethod(ReleaseMapping.memberName(owner, "void setStrength(float)"), float.class)
          .invoke(null, strength);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot set packaged dither strength", failure);
    }
  }

  private static void resize(ClientGameTestContext context, int width, int height) {
    context.runOnClient(
        client -> {
          GLFW.glfwSetWindowSize(client.getWindow().handle(), width, height);
        });
    context.getInput().resizeWindow(width, height);
    context.runOnClient(Minecraft::invalidateSurfaceConfiguration);
    context.waitTicks(10);
  }

  /** Invoked only by the test driver, immediately before the acquired image is presented. */
  public static void beforePresent(
      VulkanDevice device, long image, int format, int width, int height) {
    Request request = pending;
    if (request == null) return;
    if (draws <= request.drawsBefore) {
      if (request.waitingFrames++ == 0) {
        var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        System.out.println(
            "CBBG_WINDOW_WAIT surface="
                + width
                + "x"
                + height
                + " main="
                + main.width
                + "x"
                + main.height
                + " mode="
                + settings().get("mode")
                + " draws="
                + draws
                + " expectedAfter="
                + request.drawsBefore);
      }
      return;
    }
    pending = null;
    try {
      var output =
          ReleaseWindowPixels.screenshotOutput(
              Objects.requireNonNull(
                  Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView()));
      var commands = RenderSystem.getDevice().createCommandEncoder();
      var encoder = device.createCommandEncoder();
      try (var reference =
          device.createBuffer(
              () -> "CBBG window test reference",
              GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
              (long) width * height * 4)) {
        commands.copyTextureToBuffer(
            Objects.requireNonNull(output.getColorTexture()), reference, 0, () -> {}, 0);
        commands.submit();
        device.graphicsQueue().waitIdle();
        NativeImage imageReference = new NativeImage(width, height, false);
        try (var mapped = reference.map(true, false)) {
          ByteBuffer data = mapped.data();
          for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
              int offset = (y * width + x) * 4;
              int red = Byte.toUnsignedInt(data.get(offset));
              int green = Byte.toUnsignedInt(data.get(offset + 1));
              int blue = Byte.toUnsignedInt(data.get(offset + 2));
              int alpha = Byte.toUnsignedInt(data.get(offset + 3));
              imageReference.setPixel(
                  x, height - 1 - y, alpha << 24 | red << 16 | green << 8 | blue);
            }
          }
        }
        request.expected.complete(imageReference);
      }
      try (var pixels =
              device.createBuffer(
                  () -> "CBBG window test readback",
                  GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                  (long) width * height * 4);
          var stack = MemoryStack.stackPush()) {
        var command = encoder.allocateAndBeginTransientCommandBuffer();
        var barrier =
            VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .image(image)
                .oldLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier
            .subresourceRange()
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .baseMipLevel(0)
            .levelCount(1)
            .baseArrayLayer(0)
            .layerCount(1);
        vkCmdPipelineBarrier(
            command,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,
            null,
            null,
            barrier);
        var region =
            VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0)
                .bufferRowLength(0)
                .bufferImageHeight(0);
        region
            .imageSubresource()
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .mipLevel(0)
            .baseArrayLayer(0)
            .layerCount(1);
        region.imageExtent().set(width, height, 1);
        vkCmdCopyImageToBuffer(
            command, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pixels.vkBuffer(), region);
        barrier
            .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
            .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
            .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
            .dstAccessMask(0);
        var host =
            VkBufferMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .buffer(pixels.vkBuffer())
                .offset(0)
                .size(pixels.size())
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        vkCmdPipelineBarrier(
            command,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0,
            null,
            host,
            barrier);
        int status = vkEndCommandBuffer(command);
        if (status != VK_SUCCESS)
          throw new AssertionError("Window readback command failed: " + status);
        encoder.execute(command);
        encoder.submit();
        device.graphicsQueue().waitIdle();
        try (var mapped = pixels.map(true, false)) {
          ByteBuffer data = mapped.data();
          int[] actual = new int[width * height];
          for (int pixel = 0; pixel < actual.length; pixel++) {
            int offset = pixel * 4;
            int first = Byte.toUnsignedInt(data.get(offset));
            int green = Byte.toUnsignedInt(data.get(offset + 1));
            int third = Byte.toUnsignedInt(data.get(offset + 2));
            int alpha = Byte.toUnsignedInt(data.get(offset + 3));
            int red = format == VK_FORMAT_B8G8R8A8_UNORM ? third : first;
            int blue = format == VK_FORMAT_B8G8R8A8_UNORM ? first : third;
            actual[pixel] = alpha << 24 | red << 16 | green << 8 | blue;
          }
          request.actual.complete(actual);
        }
      }
    } catch (Throwable failure) {
      request.actual.completeExceptionally(failure);
      request.expected.completeExceptionally(failure);
    }
  }

  private static void compare(int[] actual, NativeImage expected, String mode, int iteration) {
    int width = expected.getWidth(), height = expected.getHeight();
    if (actual.length != width * height)
      throw new AssertionError("Window and screenshot dimensions differ");
    int differences = 0;
    int first = -1;
    try (var image = new NativeImage(width, height, false)) {
      for (int index = 0; index < actual.length; index++) {
        int x = index % width, y = index / width;
        image.setPixel(x, y, actual[index]);
        if (actual[index] != expected.getPixel(x, y)) {
          if (first < 0) first = index;
          differences++;
        }
      }
      Path directory = Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
      Files.createDirectories(directory);
      image.writeToFile(directory.resolve("vulkan-window-" + mode + "-" + iteration + ".png"));
      expected.writeToFile(
          directory.resolve("vulkan-window-reference-" + mode + "-" + iteration + ".png"));
    } catch (java.io.IOException failure) {
      throw new AssertionError("Could not save window pixels", failure);
    }
    if (differences != 0)
      throw new AssertionError(
          "Vulkan window differs from screenshot at "
              + differences
              + " pixels; first index "
              + first);
  }

  private static final class Request {
    int waitingFrames;
    final long drawsBefore;
    final CompletableFuture<int[]> actual = new CompletableFuture<>();
    final CompletableFuture<NativeImage> expected = new CompletableFuture<>();

    Request(long drawsBefore) {
      this.drawsBefore = drawsBefore;
    }
  }
}
