package com.qb20nh.cbbg;

import com.qb20nh.cbbg.compat.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CbbgClient implements ClientModInitializer {

  public static final String MOD_ID = "cbbg";
  public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  /**
   * Main runtime gate for all cbbg rendering changes.
   *
   * <p>Must be {@code false} when an Iris shaderpack is active to avoid conflicts.
   */
  public static boolean isEnabled() {
    return getEffectiveMode().isActive();
  }

  public static CbbgConfig.Mode getUserMode() {
    return CbbgConfig.get().mode();
  }

  public static CbbgConfig.Mode getEffectiveMode() {
    // Iris shaderpacks must take full control of the pipeline.
    if (IrisCompat.isShaderPackActive()) {
      return CbbgConfig.Mode.DISABLED;
    }
    return CbbgConfig.get().mode();
  }

  public static boolean isDemoMode() {
    return getEffectiveMode() == CbbgConfig.Mode.DEMO;
  }

  @Override
  public void onInitializeClient() {
    // Ensure config is loaded early.
    CbbgConfig.get();
    LOGGER.info("cbbg loaded");
  }
}
