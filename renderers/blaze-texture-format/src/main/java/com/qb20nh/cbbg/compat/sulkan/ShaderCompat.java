package com.qb20nh.cbbg.compat.sulkan;

import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ShaderCompat {
  private ShaderCompat() {}

  public static boolean isSulkanActive() {
    return false;
  }
}
