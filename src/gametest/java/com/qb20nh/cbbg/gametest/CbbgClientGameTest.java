package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

public class CbbgClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!FabricLoader.getInstance().isModLoaded("cbbg")) {
            throw new AssertionError("Expected main mod 'cbbg' to be loaded");
        }
        // World creation is handled by cbbg itself (not by the Fabric client-gametest helper),
        // because older client-gametest module versions have mixin incompatibilities with
        // Minecraft 1.21.1's Create World screen. This test focuses on validating that the mod
        // can render and take screenshots without crashing.
        context.waitTicks(40);
        context.takeScreenshot("cbbg-client-smoke");
    }
}
