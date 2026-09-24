package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Also runnable without CBBG as a control for native surface resize failures. */
public final class WindowResizeGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        for (int iteration = 0; iteration < 5; iteration++) {
            context.getInput().resizeWindow(854, 480);
            context.waitTicks(10);
            context.getInput().resizeWindow(960, 540);
            context.waitTicks(10);
        }
    }
}
