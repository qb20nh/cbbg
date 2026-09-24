package com.qb20nh.cbbg;

import com.qb20nh.cbbg.config.CbbgConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.LoggerFactory;

public final class CbbgEarlyInit implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        CbbgConfig.configure(FabricLoader.getInstance().getConfigDir().resolve("cbbg.json"),
                LoggerFactory.getLogger("cbbg")::warn);
    }
}
