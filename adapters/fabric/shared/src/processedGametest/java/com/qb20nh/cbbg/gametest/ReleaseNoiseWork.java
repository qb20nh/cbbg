package com.qb20nh.cbbg.gametest;

import com.qb20nh.cbbg.api.NoiseVolume;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseNoiseWork {
  private ReleaseNoiseWork() {}

  static boolean mathActive() {
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.getName().equals("cbbg-stbn") && thread.isAlive() && mathActive(thread))
        return true;
    }
    return false;
  }

  static boolean mathActive(Thread thread) {
    for (StackTraceElement frame : thread.getStackTrace()) {
      if (frame.getClassName().equals(NoiseVolume.class.getName())
          && frame.getMethodName().equals("generate")) return true;
    }
    return false;
  }
}
