package com.qb20nh.cbbg;

import com.qb20nh.cbbg.render.STBNGenerator;
import de.florianmichael.asmfabricloader.api.event.PrePrePreLaunchEntrypoint;

public class CbbgEarlyInit implements PrePrePreLaunchEntrypoint {

    @Override
    public void onLanguageAdapterLaunch() {
        STBNGenerator.init();
    }
}
