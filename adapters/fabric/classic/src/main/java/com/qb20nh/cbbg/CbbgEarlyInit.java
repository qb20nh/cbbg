package com.qb20nh.cbbg;

import com.qb20nh.cbbg.config.CbbgConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class CbbgEarlyInit implements PreLaunchEntrypoint {
  @Override
  public void onPreLaunch() {
    CbbgConfig.configure(
        FabricLoader.getInstance().getConfigDir().resolve("cbbg.json"),
        (message, failure) -> Cbbg.LOGGER.warn(message, failure));
    CbbgConfig.get();
  }
}
