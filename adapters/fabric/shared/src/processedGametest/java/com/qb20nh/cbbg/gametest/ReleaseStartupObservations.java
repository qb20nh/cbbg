package com.qb20nh.cbbg.gametest;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NullMarked;

/** Counts completed calls to a CBBG GPU render pass from test-side mixins. */
@NullMarked
public final class ReleaseStartupObservations {
  private static final AtomicLong FIRST_DRAW = new AtomicLong();
  private static final AtomicLong DRAWS = new AtomicLong();

  private ReleaseStartupObservations() {}

  public static void draw() {
    FIRST_DRAW.compareAndSet(0, ManagementFactory.getRuntimeMXBean().getUptime());
    DRAWS.incrementAndGet();
    ReleaseShutdownGameTest.observeDraw();
  }

  static long firstDrawMillis() {
    return FIRST_DRAW.get();
  }

  static long draws() {
    return DRAWS.get();
  }
}
