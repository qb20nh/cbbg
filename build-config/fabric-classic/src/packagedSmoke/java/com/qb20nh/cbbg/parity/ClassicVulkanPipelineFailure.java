package com.qb20nh.cbbg.parity;

import java.lang.reflect.Method;
import java.nio.LongBuffer;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/** Drives a packaged client through pipeline failure, vanilla presentation, and resource reload. */
public final class ClassicVulkanPipelineFailure {
  private static final String FAULT_PROPERTY = "cbbg.test.vulkanPipelineFault";
  private static int attempts;
  private static int descriptorCalls;
  private static int cleanedPipelines;
  private static Method createPipeline;
  private final String fault = System.getProperty(FAULT_PROPERTY);
  private int attemptsAtFallback;
  private long fellBackAt = -1;
  private long reloadedAt = -1;
  private CompletableFuture<Void> reload;

  public static String fragmentSource(String name, String source) {
    if ("shader".equals(System.getProperty(FAULT_PROPERTY))
        && "cbbg_vulkanmod_dither.fsh".equals(name)) {
      attempts++;
      return "#version 450\n#error CBBG test shader failure\n";
    }
    return source;
  }

  static boolean rejectDescriptorSets() {
    if ("constructor".equals(System.getProperty(FAULT_PROPERTY))
        && ClassicVulkanAllocation.hasCbbgCaller()
        && ++descriptorCalls % 2 == 0) {
      attempts++;
      return true;
    }
    return false;
  }

  public static int createGraphicsPipelines(
      Object device, long cache, Object info, Object callbacks, LongBuffer output)
      throws ReflectiveOperationException {
    if ("native".equals(System.getProperty(FAULT_PROPERTY))
        && ClassicVulkanAllocation.hasCbbgCaller()) {
      attempts++;
      return -2; // VK_ERROR_OUT_OF_DEVICE_MEMORY
    }
    if (createPipeline == null) {
      createPipeline =
          Class.forName("org.lwjgl.vulkan.VK10")
              .getMethod(
                  "vkCreateGraphicsPipelines",
                  device.getClass(),
                  long.class,
                  info.getClass(),
                  Class.forName("org.lwjgl.vulkan.VkAllocationCallbacks"),
                  LongBuffer.class);
    }
    return (int) createPipeline.invoke(null, device, cache, info, callbacks, output);
  }

  public static void observeCleanup(Object pipeline) throws ReflectiveOperationException {
    if ("cbbg_vulkanmod_dither".equals(pipeline.getClass().getField("name").get(pipeline))) {
      cleanedPipelines++;
    }
  }

  boolean tick(Minecraft client, long presentations) throws Exception {
    if (fellBackAt < 0) {
      // The initial resource reload can finish after the first attempted frame.
      attemptsAtFallback = attempts;
      verifyFailure();
      ClassicVulkanSmoke.checkSceneFormat(44);
      ClassicVulkanSmoke.capture(client, "vulkan-pipeline-fallback.png", false);
      fellBackAt = presentations;
    } else if (reload == null && presentations >= fellBackAt + 12) {
      verifyFailure();
      System.clearProperty(FAULT_PROPERTY);
      reload = client.reloadResourcePacks();
    } else if (reload != null && reload.isDone() && client.getOverlay() == null) {
      reload.join();
      if (reloadedAt < 0) reloadedAt = presentations;
      if (presentations >= reloadedAt + 12) {
        if (ClassicVulkanSmoke.ditherDraws == 0) {
          throw new AssertionError("Dither draw was not restored after resource reload");
        }
        ClassicVulkanSmoke.checkSceneFormat(97);
        ClassicVulkanSmoke.capture(client, "vulkan-world.png", true);
        System.out.println(
            "CBBG_CLASSIC_VULKAN_PIPELINE_RECOVERED fault="
                + fault
                + " attempts="
                + attempts
                + " cleaned="
                + cleanedPipelines);
        return true;
      }
    }
    return false;
  }

  private void verifyFailure() {
    int expectedCleanups = "native".equals(fault) ? attemptsAtFallback : 0;
    if (attempts < 1
        || attempts > 2
        || attempts != attemptsAtFallback
        || cleanedPipelines != expectedCleanups
        || ClassicVulkanSmoke.ditherDraws != 0) {
      throw new AssertionError(
          "Wrong failure behavior: attempts="
              + attempts
              + ", cleaned="
              + cleanedPipelines
              + ", draws="
              + ClassicVulkanSmoke.ditherDraws);
    }
    if (Boolean.getBoolean("cbbg.test.vulkanTrackConstruction")) {
      ClassicVulkanPipelineConstruction.verifyReleased(attempts, "constructor".equals(fault));
    }
  }
}
