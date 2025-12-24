package com.qb20nh.cbbg;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import de.florianmichael.asmfabricloader.api.event.PrePrePreLaunchEntrypoint;

public class CbbgEarlyInit implements PrePrePreLaunchEntrypoint {

    @Override
    public void onLanguageAdapterLaunch() {
        // We can try to load config here. Fabric Loader should be initialized enough.
        // If not, we fall back to defaults effectively by CbbgConfig handling exceptions?
        // Actually CbbgConfig.get() is safe.
        CbbgConfig cfg = CbbgConfig.get();
        STBNGenerator.generateAsync(cfg.stbnSize(), cfg.stbnSize(), cfg.stbnDepth(),
                cfg.stbnSeed());
    }
}
