package com.qb20nh.cbbg;

import com.qb20nh.cbbg.config.CbbgConfig;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class CbbgEarlyInit implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        CbbgConfig.configure(FabricLoader.getInstance().getConfigDir().resolve("cbbg.json"),
                (message, failure) -> Logger.getLogger("cbbg").log(Level.WARNING, message, failure));
        CbbgConfig.get();
    }
}
