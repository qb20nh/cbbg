package com.qb20nh.cbbg;

import com.qb20nh.cbbg.compat.IrisCompat;
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
    return !IrisCompat.isShaderPackActive();
  }

  @Override
  public void onInitializeClient() {
    LOGGER.info("cbbg loaded");
  }
}
