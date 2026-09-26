package com.qb20nh.cbbg.gametest;

import com.qb20nh.cbbg.config.CbbgConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.LoggerFactory;

/** Bounds noise-generation work in the isolated test directory. */
public final class TestNoiseSettings implements PreLaunchEntrypoint {
  @Override
  public void onPreLaunch() {
    CbbgConfig.configure(
        FabricLoader.getInstance().getConfigDir().resolve("cbbg.json"),
        LoggerFactory.getLogger("cbbg-test")::warn);
    CbbgConfig.setStbnSize(16);
    CbbgConfig.setStbnDepth(8);
  }
}
