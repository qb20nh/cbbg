package com.qb20nh.cbbg.smoke;

import com.qb20nh.cbbg.config.gui.CbbgConfigScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

final class LegacyMenuParity {
    static int run() {
        boolean present = FabricLoader.getInstance().isModLoaded("modmenu");
        if (present != Boolean.getBoolean("cbbg.test.modMenu")) throw new AssertionError("Wrong Mod Menu fixture");
        return present ? 1 + Present.run() : 1;
    }

    private static final class Present {
        static int run() {
            if (!io.github.prospector.modmenu.ModMenu.hasFactory("cbbg")) {
                throw new AssertionError("Mod Menu did not discover the CBBG entry point");
            }
            MinecraftClient client = MinecraftClient.getInstance();
            Screen parent = client.currentScreen;
            Screen screen = io.github.prospector.modmenu.ModMenu.getConfigScreen("cbbg", parent);
            if (!(screen instanceof CbbgConfigScreen)) throw new AssertionError("Wrong config screen factory");
            try {
                client.setScreen(screen);
                screen.render(-1, -1, 0);
            } finally {
                client.setScreen(parent);
            }
            return 3;
        }
    }
}
