package com.qb20nh.cbbg.render;

import static org.lwjgl.vulkan.KHRDynamicRendering.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

import it.unimi.dsi.fastutil.longs.LongList;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Owns views of borrowed swapchain images; the surface retains image ownership. */
@NullMarked
public final class VulkanWindowTarget {
  private long[] views = new long[0];

  private boolean colorAttachment;

  public int configureImageUsage(int usage, VkSurfaceCapabilitiesKHR capabilities) {
    colorAttachment =
        (capabilities.supportedUsageFlags() & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0;
    return colorAttachment ? usage | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : usage;
  }

  public boolean canDraw(boolean outOfDate, int index, int format) {
    return colorAttachment
        && !outOfDate
        && index >= 0
        && (format == VK_FORMAT_R8G8B8A8_UNORM || format == VK_FORMAT_B8G8R8A8_UNORM);
  }

  public void close(VkDevice device) {
    for (long view : views) if (view != 0) vkDestroyImageView(device, view, null);
    views = new long[0];
  }

  public void begin(
      VkDevice device,
      VkCommandBuffer command,
      LongList images,
      int index,
      int format,
      int width,
      int height,
      MemoryStack stack) {
    if (views.length != images.size()) views = new long[images.size()];
    if (views[index] == 0) {
      var info =
          VkImageViewCreateInfo.calloc(stack)
              .sType$Default()
              .image(images.getLong(index))
              .viewType(VK_IMAGE_VIEW_TYPE_2D)
              .format(format);
      info.subresourceRange()
          .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
          .baseMipLevel(0)
          .levelCount(1)
          .baseArrayLayer(0)
          .layerCount(1);
      var result = stack.mallocLong(1);
      check(vkCreateImageView(device, info, null, result));
      views[index] = result.get(0);
    }
    barrier(
        stack,
        command,
        images.getLong(index),
        VK_IMAGE_LAYOUT_UNDEFINED,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        0,
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
    var attachment =
        VkRenderingAttachmentInfoKHR.calloc(1, stack)
            .sType$Default()
            .imageView(views[index])
            .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
            .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
            .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
    var rendering =
        VkRenderingInfoKHR.calloc(stack)
            .sType$Default()
            .layerCount(1)
            .pColorAttachments(attachment);
    rendering.renderArea().offset().set(0, 0);
    rendering.renderArea().extent().set(width, height);
    vkCmdBeginRenderingKHR(command, rendering);
  }

  public void end(VkCommandBuffer command, long image, MemoryStack stack) {
    vkCmdEndRenderingKHR(command);
    barrier(
        stack,
        command,
        image,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
        0,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
  }

  public static void check(int result) {
    if (result != VK_SUCCESS)
      throw new IllegalStateException("CBBG Vulkan window operation failed: " + result);
  }

  private static void barrier(
      MemoryStack stack,
      VkCommandBuffer command,
      long image,
      int oldLayout,
      int newLayout,
      int sourceAccess,
      int destinationAccess,
      int sourceStage,
      int destinationStage) {
    var barrier =
        VkImageMemoryBarrier.calloc(1, stack)
            .sType$Default()
            .image(image)
            .oldLayout(oldLayout)
            .newLayout(newLayout)
            .srcAccessMask(sourceAccess)
            .dstAccessMask(destinationAccess)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
    barrier
        .subresourceRange()
        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
        .baseMipLevel(0)
        .levelCount(1)
        .baseArrayLayer(0)
        .layerCount(1);
    vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0, null, null, barrier);
  }
}
