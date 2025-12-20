package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

public class CbbgClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!FabricLoader.getInstance().isModLoaded("cbbg")) {
            throw new AssertionError("Expected main mod 'cbbg' to be loaded");
        }

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            context.takeScreenshot("cbbg-client-smoke");
        }
    }
}


