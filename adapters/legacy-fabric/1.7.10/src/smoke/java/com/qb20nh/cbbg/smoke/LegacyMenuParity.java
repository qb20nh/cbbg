package com.qb20nh.cbbg.smoke;

import net.fabricmc.loader.api.FabricLoader;

final class LegacyMenuParity {
    static int run() {
        if (Boolean.getBoolean("cbbg.test.modMenu")
                || FabricLoader.getInstance().isModLoaded("modmenu")) {
            throw new AssertionError("Wrong Mod Menu fixture for standalone 1.7.10");
        }
        return 1;
    }
}
