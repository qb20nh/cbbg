package com.qb20nh.cbbg.parity;

import it.unimi.dsi.fastutil.shorts.Short2LongMap;
import java.lang.reflect.Method;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/** Exercises sampler failure after a real image and its view have been allocated. */
public final class ClassicVulkanSamplerFailure {
  private static final String FAULT = "cbbg.test.vulkanSamplerFault";
  private static final List<Object> abandoned = new ArrayList<>();
  private static boolean rejectSampler;
  private static boolean partialNoiseBatch;
  private static int rejected;
  private static Method nativeCreate;
  private final boolean scene = "scene".equals(System.getProperty(FAULT));
  private long fallbackAt = -1;
  private long reloadedAt = -1;
  private CompletableFuture<Void> reload;

  public static void observeImage(Object image) throws ReflectiveOperationException {
    String fault = System.getProperty(FAULT, "");
    if (fault.isEmpty() || !ClassicVulkanAllocation.hasCbbgCaller()) return;
    int format = image.getClass().getField("format").getInt(image);
    int usage = image.getClass().getField("usage").getInt(image);
    if (fault.equals("scene") && format == 97 && usage == 0x14) {
      rejectSampler = true;
    } else if (fault.equals("noise") && format == 37 && usage == 0x6) {
      // Let the first layer upload before failing the next layer in the batch.
      rejectSampler = partialNoiseBatch;
      partialNoiseBatch = !partialNoiseBatch;
    } else {
      return;
    }
    if ((long) image.getClass().getMethod("getId").invoke(image) == 0
        || (long) image.getClass().getMethod("getImageView").invoke(image) == 0) {
      throw new AssertionError("Sampler allocation was reached before an image and view existed");
    }
    abandoned.add(image);
  }

  public static long samplerCache(Short2LongMap samplers, short key, long missing) {
    return rejectSampler ? missing : samplers.getOrDefault(key, missing);
  }

  public static int createSampler(Object device, Object info, Object callbacks, LongBuffer output)
      throws ReflectiveOperationException {
    if (rejectSampler) {
      rejectSampler = false;
      rejected++;
      System.out.println("CBBG_CLASSIC_VULKAN_SAMPLER_REJECTED count=" + rejected);
      return -2; // VK_ERROR_OUT_OF_DEVICE_MEMORY
    }
    return (int) samplerMethod(device, info).invoke(null, device, info, callbacks, output);
  }

  private static synchronized Method samplerMethod(Object device, Object info)
      throws ReflectiveOperationException {
    if (nativeCreate == null) {
      nativeCreate =
          Class.forName("org.lwjgl.vulkan.VK10")
              .getMethod(
                  "vkCreateSampler",
                  device.getClass(),
                  info.getClass(),
                  Class.forName("org.lwjgl.vulkan.VkAllocationCallbacks"),
                  LongBuffer.class);
    }
    return nativeCreate;
  }

  boolean tick(Minecraft client, long presentations) throws Exception {
    if (fallbackAt < 0) {
      if (rejected == 0) return false;
      verifyFallback();
      ClassicVulkanSmoke.checkSceneFormat(scene ? 37 : 97);
      ClassicVulkanSmoke.capture(client, "vulkan-sampler-fallback.png", true);
      fallbackAt = presentations;
    } else if (reload == null && presentations >= fallbackAt + 12) {
      verifyFallback();
      System.clearProperty(FAULT);
      reload = client.reloadResourcePacks();
    } else if (reload != null && reload.isDone() && client.getOverlay() == null) {
      reload.join();
      if (reloadedAt < 0) reloadedAt = presentations;
      if (presentations >= reloadedAt + 12 && ClassicVulkanSmoke.noiseWidth == 16) {
        ClassicVulkanSmoke.checkSceneFormat(97);
        ClassicVulkanSmoke.capture(client, "vulkan-world.png", true);
        System.out.println(
            "CBBG_CLASSIC_VULKAN_SAMPLER_RECOVERED scene="
                + scene
                + " failures="
                + rejected
                + " freedImages="
                + abandoned.size());
        return true;
      }
    }
    return false;
  }

  private void verifyFallback() throws ReflectiveOperationException {
    if (rejected != 1 || abandoned.size() != (scene ? 1 : 2)) {
      throw new AssertionError("Unexpected sampler retries or incomplete batch tracking");
    }
    if (!scene && ClassicVulkanSmoke.noiseWidth == 16) {
      throw new AssertionError("A partially uploaded noise batch was used for presentation");
    }
    for (Object image : abandoned) {
      if ((long) image.getClass().getMethod("getId").invoke(image) != 0) {
        throw new AssertionError("Sampler failure leaked a texture image and view");
      }
    }
  }
}
