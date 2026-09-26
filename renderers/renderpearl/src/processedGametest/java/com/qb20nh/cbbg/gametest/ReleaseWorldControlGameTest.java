package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Captures the shared scene with CBBG disabled in a separate game process. */
public final class ReleaseWorldControlGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ReleaseClient.checkArtifactAndBackend(context);
        ReleaseWorldPixelsGameTest.runScene(context, true);
    }
}
