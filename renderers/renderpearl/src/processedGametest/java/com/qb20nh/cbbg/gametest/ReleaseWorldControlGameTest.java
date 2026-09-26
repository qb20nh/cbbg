package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;

/** Captures the shared scene with CBBG disabled in a separate game process. */
@NullMarked
public final class ReleaseWorldControlGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseClient.checkArtifactAndBackend(context);
    ReleaseWorldPixelsGameTest.runScene(context, true);
  }
}
