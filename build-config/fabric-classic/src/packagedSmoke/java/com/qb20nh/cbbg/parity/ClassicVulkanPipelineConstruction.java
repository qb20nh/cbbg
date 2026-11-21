package com.qb20nh.cbbg.parity;

import java.lang.reflect.Method;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Records allocations owned by the failed constructor without replacing successful native calls.
 */
public final class ClassicVulkanPipelineConstruction {
  private static final Map<String, Method> METHODS = new HashMap<>();
  private static final Map<String, Set<Long>> handles = new HashMap<>();
  private static final Map<Object, String> buffers = new IdentityHashMap<>();
  private static final Map<String, Integer> allocations = new HashMap<>();

  private ClassicVulkanPipelineConstruction() {}

  public static int createHandle(
      Object device, Object info, Object callbacks, LongBuffer output, String resource)
      throws ReflectiveOperationException {
    String name = "vkCreate" + resource;
    Method method = METHODS.get(name);
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
    if (result == 0) observeHandle(output.get(0), resource);
    return result;
  }

  public static int createSets(Object device, Object info, LongBuffer output)
      throws ReflectiveOperationException {
    if (ClassicVulkanPipelineFailure.rejectDescriptorSets()) return -2;
    String name = "vkAllocateDescriptorSets";
    Method method = METHODS.get(name);
    if (method == null) method = lookup(name, device.getClass(), info.getClass(), LongBuffer.class);
    return (int) method.invoke(null, device, info, output);
  }

  private static Method lookup(String name, Class<?>... parameters)
      throws ReflectiveOperationException {
    Method method = Class.forName("org.lwjgl.vulkan.VK10").getMethod(name, parameters);
    METHODS.put(name, method);
    return method;
  }

  public static void observeHandle(long handle, String resource) {
    if (handle == 0 || !tracking()) return;
    if (!handles.computeIfAbsent(resource, ignored -> new HashSet<>()).add(handle)) {
      throw new AssertionError("Allocated an already live " + resource);
    }
    allocations.merge(resource, 1, Integer::sum);
  }

  public static void releaseHandle(long handle, String resource) {
    Set<Long> live = handles.get(resource);
    if (live != null) live.remove(handle);
  }

  public static void observeBuffer(Object buffer, String kind) {
    if (!tracking()) return;
    if (buffers.put(buffer, kind) != null) throw new AssertionError("Reused an owned buffer");
    allocations.merge(kind, 1, Integer::sum);
  }

  public static void releaseBuffer(Object buffer) {
    buffers.remove(buffer);
  }

  private static boolean tracking() {
    String fault = System.getProperty("cbbg.test.vulkanPipelineFault", "");
    return (fault.equals("constructor") || fault.equals("shader"))
        && ClassicVulkanAllocation.hasCbbgCaller();
  }

  static void verifyReleased(int attempts, boolean constructor) {
    if (!buffers.isEmpty() || handles.values().stream().anyMatch(live -> !live.isEmpty())) {
      throw new AssertionError(
          "Pipeline construction leaked native resources: "
              + handles
              + ", buffers="
              + buffers.values());
    }
    Map<String, Integer> expected = new HashMap<>(Map.of("ShaderResult", 2 * attempts));
    if (constructor) {
      for (String kind :
          Set.of("DescriptorSetLayout", "PipelineLayout", "VertexBindings", "VertexAttributes"))
        expected.put(kind, attempts);
      for (String kind :
          Set.of("ShaderModule", "DescriptorPool", "DynamicOffsets", "DescriptorHandles"))
        expected.put(kind, 2 * attempts);
    }
    if (!expected.equals(allocations)) {
      throw new AssertionError(
          "Wrong failure allocations: " + allocations + ", expected " + expected);
    }
    System.out.println("CBBG_CLASSIC_VULKAN_CONSTRUCTION_RELEASED " + allocations);
  }
}
