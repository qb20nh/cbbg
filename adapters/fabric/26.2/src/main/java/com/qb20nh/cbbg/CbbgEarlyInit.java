package com.qb20nh.cbbg;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import de.florianmichael.asmfabricloader.api.event.PrePrePreLaunchEntrypoint;
import net.fabricmc.loader.api.FabricLoader;

public class CbbgEarlyInit implements PrePrePreLaunchEntrypoint {

    @Override
    public void onLanguageAdapterLaunch() {
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
