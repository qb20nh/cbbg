package com.qb20nh.cbbg.parity;

import java.lang.reflect.Method;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;

/** Uses a test-only agent to substitute a VMA result before an allocation takes place. */
public final class ClassicVulkanAllocation {
  private static final int RGBA8 = 37;
  private static final int RGBA16F = 97;
  private static final int RGBA32F = 109;
  private static final int OUT_OF_DEVICE_MEMORY = -2;
  private static final String PROFILE = System.getProperty("cbbg.test.vulkanAllocation", "");
  private static final StackWalker STACK =
      StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
  private static Method nativeAllocate;
  private static Method format;
  private static Method usage;
  private static long partialColor;
  private static int partialFrees;
  private static boolean depthRejected;

  private ClassicVulkanAllocation() {}

  public static int createImage(
      long allocator,
      Object info,
      Object options,
      LongBuffer image,
      PointerBuffer allocation,
      Object details)
      throws ReflectiveOperationException {
    if (nativeAllocate == null) {
      nativeAllocate =
          Class.forName("org.lwjgl.util.vma.Vma")
              .getMethod(
                  "vmaCreateImage",
                  long.class,
                  info.getClass(),
                  options.getClass(),
                  LongBuffer.class,
                  PointerBuffer.class,
                  Class.forName("org.lwjgl.util.vma.VmaAllocationInfo"));
      format = info.getClass().getMethod("format");
      usage = info.getClass().getMethod("usage");
    }
    int colorFormat = (int) format.invoke(info);
    int flags = (int) usage.invoke(info);
    boolean isColor =
        flags == 0x14 && (colorFormat == RGBA8 || colorFormat == RGBA16F || colorFormat == RGBA32F);
    boolean isDepth = flags == 0x24;
    boolean scene = (isColor || isDepth) && hasCbbgCaller();
    if (scene
        && isColor
        && (PROFILE.equals("all") || (PROFILE.equals("float") && colorFormat != RGBA8))) {
      return reject(colorFormat, "color");
    }
    if (scene && isDepth && partialColor != 0 && !depthRejected) {
      depthRejected = true;
      return reject(colorFormat, "depth");
    }
    int result =
        (int) nativeAllocate.invoke(null, allocator, info, options, image, allocation, details);
    if (scene && result == 0) ClassicVulkanNativeAllocation.observeImage(image.get(0));
    if (depthRejected && result == 0 && image.get(0) == partialColor) partialColor = 0;
    if (scene && isColor && result == 0 && !depthRejected && isPartialColor(colorFormat, info)) {
      partialColor = image.get(0);
    }
    return result;
  }

  static boolean hasCbbgCaller() {
    return STACK.walk(
        stack ->
            stack
                .map(StackWalker.StackFrame::getDeclaringClass)
                .map(type -> type.getProtectionDomain().getCodeSource())
                .anyMatch(
                    source ->
                        source != null && source.getLocation().getPath().endsWith("-fabric.jar")));
  }

  private static boolean isPartialColor(int colorFormat, Object info)
      throws ReflectiveOperationException {
    if (PROFILE.equals("depth")) return colorFormat == RGBA32F;
    if (!PROFILE.equals("resize") || colorFormat != RGBA16F) return false;
    Object extent = info.getClass().getMethod("extent").invoke(info);
    Class<?> type = extent.getClass();
    return (int) type.getMethod("width").invoke(extent) == 736
        && (int) type.getMethod("height").invoke(extent) == 552;
  }

  private static int reject(int format, String attachment) {
    System.out.println("CBBG_CLASSIC_VULKAN_REJECT " + attachment + " format=" + format);
    return OUT_OF_DEVICE_MEMORY;
  }

  public static void observeFree(long image) {
    ClassicVulkanNativeAllocation.observeImageFree(image);
    if (image != 0 && image == partialColor && depthRejected) {
      partialFrees++;
      System.out.println("CBBG_CLASSIC_VULKAN_PARTIAL_FREE count=" + partialFrees);
    }
  }
}
