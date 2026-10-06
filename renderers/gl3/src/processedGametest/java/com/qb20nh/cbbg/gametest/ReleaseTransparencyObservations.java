package com.qb20nh.cbbg.gametest;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.PostChain;
import org.jspecify.annotations.NullMarked;

/** Actual completed post-processing passes, without retaining closed world chains. */
@NullMarked
public final class ReleaseTransparencyObservations {
  private static final Map<PostChain, Long> PASSES = new WeakHashMap<>();

  private ReleaseTransparencyObservations() {}

  public static void processed(PostChain chain) {
    PASSES.merge(chain, 1L, Long::sum);
  }

  static long passes(PostChain chain) {
    return PASSES.getOrDefault(chain, 0L);
  }
}
