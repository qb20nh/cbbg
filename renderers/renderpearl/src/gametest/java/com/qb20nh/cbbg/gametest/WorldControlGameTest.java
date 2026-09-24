package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Same scene with CBBG disabled, for distinguishing renderer output from effect regressions. */
public final class WorldControlGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        WorldPixelsGameTest.runScene(context, true);
    }
}
