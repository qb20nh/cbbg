package com.qb20nh.cbbg;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public class CbbgEarlyInit implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        startPreparation();
    }

    static void startPreparation() {
        configureSettings();
        CbbgConfig cfg = CbbgConfig.get();
        STBNGenerator.generateAsync(cfg.stbnSize(), cfg.stbnSize(), cfg.stbnDepth(),
                cfg.stbnSeed());
    }

    static void configureSettings() {
        CbbgConfig.configure(FabricLoader.getInstance().getConfigDir().resolve(Cbbg.MOD_ID + ".json"),
                Cbbg.LOGGER::warn);
    }
}
