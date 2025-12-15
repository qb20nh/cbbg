package com.qb20nh.cbbg.render;

import com.qb20nh.cbbg.CbbgClient;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks whether RGBA16F is usable on the current device.
 *
 * <p>If we detect an OpenGL error allocating the main target as RGBA16F, we fall back to vanilla
 * RGBA8 for the remainder of the session to avoid hard-crashes.
 */
public final class Rgba16fSupport {

  private static volatile boolean enabled = true;
  private static final AtomicBoolean loggedDisable = new AtomicBoolean(false);

  private Rgba16fSupport() {}

  public static boolean isEnabled() {
    return enabled;
  }

  public static void disable(Throwable cause) {
    enabled = false;
    if (!loggedDisable.compareAndSet(false, true)) {
      return;
    }

    if (cause == null) {
      CbbgClient.LOGGER.warn("RGBA16F main render target disabled; falling back to RGBA8.");
    } else {
      CbbgClient.LOGGER.warn(
          "RGBA16F main render target failed to allocate; falling back to RGBA8.", cause);
    }
  }
}
