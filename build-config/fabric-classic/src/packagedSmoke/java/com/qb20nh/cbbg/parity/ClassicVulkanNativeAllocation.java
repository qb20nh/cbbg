package com.qb20nh.cbbg.parity;

import java.lang.reflect.Method;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Records the real handles created before the injected failure and their later disposal. */
public final class ClassicVulkanNativeAllocation {
  private static final String FAIL = System.getProperty("cbbg.test.vulkanNativeFailure", "");
  private static final Map<String, Method> CALLS = new HashMap<>();
  private static final Map<String, Set<Long>> LIVE = new HashMap<>();
  private static int targetCalls;
  private static boolean rejected;

  private ClassicVulkanNativeAllocation() {}

  static void observeImage(long image) {
    if (!FAIL.isEmpty() && !rejected) track("Image", image);
  }

  public static void observeImageFree(long image) {
    release("Image", image);
  }

  public static int createImageView(Object device, Object info, Object callbacks, LongBuffer result)
      throws ReflectiveOperationException {
    return create("ImageView", device, info, callbacks, result);
  }

  public static int createRenderPass(
      Object device, Object info, Object callbacks, LongBuffer result)
      throws ReflectiveOperationException {
    return create("RenderPass", device, info, callbacks, result);
  }

  public static int createFramebuffer(
      Object device, Object info, Object callbacks, LongBuffer result)
      throws ReflectiveOperationException {
    return create("Framebuffer", device, info, callbacks, result);
  }

  public static void destroyImageView(Object device, long handle, Object callbacks)
      throws ReflectiveOperationException {
    destroy("ImageView", device, handle, callbacks);
  }

  public static void destroyRenderPass(Object device, long handle, Object callbacks)
      throws ReflectiveOperationException {
    destroy("RenderPass", device, handle, callbacks);
  }

  public static void destroyFramebuffer(Object device, long handle, Object callbacks)
      throws ReflectiveOperationException {
    destroy("Framebuffer", device, handle, callbacks);
  }

  private static int create(
      String resource, Object device, Object info, Object callbacks, LongBuffer output)
      throws ReflectiveOperationException {
    boolean capture = !FAIL.isEmpty() && !rejected && ClassicVulkanAllocation.hasCbbgCaller();
    if (capture && FAIL.equals(resource) && ++targetCalls == 2) {
      rejected = true;
      System.out.println("CBBG_CLASSIC_VULKAN_NATIVE_FAIL resource=" + resource);
      return -2; // VK_ERROR_OUT_OF_DEVICE_MEMORY
    }
    String name = "vkCreate" + resource;
    Method method = CALLS.get(name);
    if (method == null) {
      method =
          lookup(
              name,
              device.getClass(),
              info.getClass(),
              Class.forName("org.lwjgl.vulkan.VkAllocationCallbacks"),
              LongBuffer.class);
    }
    int result = (int) method.invoke(null, device, info, callbacks, output);
    if (capture && result == 0) track(resource, output.get(0));
    return result;
  }

  private static void destroy(String resource, Object device, long handle, Object callbacks)
      throws ReflectiveOperationException {
    String name = "vkDestroy" + resource;
    Method method = CALLS.get(name);
    if (method == null) {
      method =
          lookup(
              name,
              device.getClass(),
              long.class,
              Class.forName("org.lwjgl.vulkan.VkAllocationCallbacks"));
    }
    method.invoke(null, device, handle, callbacks);
    release(resource, handle);
  }

  private static Method lookup(String name, Class<?>... parameters)
      throws ReflectiveOperationException {
    Method result = Class.forName("org.lwjgl.vulkan.VK10").getMethod(name, parameters);
    CALLS.put(name, result);
    return result;
  }

  private static void track(String name, long handle) {
    LIVE.computeIfAbsent(name, ignored -> new HashSet<>()).add(handle);
    System.out.println("CBBG_CLASSIC_VULKAN_NATIVE_CREATED resource=" + name);
  }

  private static void release(String name, long handle) {
    Set<Long> handles = LIVE.get(name);
    if (handles != null && handles.remove(handle)) {
      System.out.println("CBBG_CLASSIC_VULKAN_NATIVE_RELEASED resource=" + name);
    }
  }
}
