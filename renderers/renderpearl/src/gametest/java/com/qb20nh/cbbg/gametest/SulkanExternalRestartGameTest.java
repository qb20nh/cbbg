package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

public final class SulkanExternalRestartGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        SulkanRestartGameTest.run(context, "cbbg-native-test");
    }
}
