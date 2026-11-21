package com.qb20nh.cbbg.render.vulkanmod;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongConsumer;

/** Owns partial allocations until VulkanMod returns a complete dither pipeline. */
public final class VulkanPipelineBuild implements AutoCloseable {
  public static final String NAME = "cbbg_vulkanmod_dither";

  private static final ThreadLocal<VulkanPipelineBuild> ACTIVE = new ThreadLocal<>();
  private final Deque<Runnable> rollback = new ArrayDeque<>();
  private final Deque<Runnable> temporary = new ArrayDeque<>();

  private VulkanPipelineBuild() {}

  static VulkanPipelineBuild begin() {
    var build = new VulkanPipelineBuild();
    ACTIVE.set(build);
    return build;
  }

  public static boolean isActive() {
    return ACTIVE.get() != null;
  }

  public static void own(Runnable release) {
    var build = ACTIVE.get();
    if (build != null) build.rollback.push(release);
  }

  public static void releaseOnClose(Runnable release) {
    var build = ACTIVE.get();
    if (build != null) build.temporary.push(release);
  }

  public static int ownHandle(int result, LongBuffer output, LongConsumer release) {
    if (result == VK_SUCCESS && isActive()) {
      long handle = output.get(0);
      own(() -> release.accept(handle));
    }
    return result;
  }

  void commit() {
    rollback.clear();
  }

  @Override
  public void close() {
    ACTIVE.remove();
    while (!rollback.isEmpty()) rollback.pop().run();
    while (!temporary.isEmpty()) temporary.pop().run();
  }
}
