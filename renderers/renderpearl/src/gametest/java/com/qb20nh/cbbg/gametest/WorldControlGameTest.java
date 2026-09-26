package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;

/** Same scene with CBBG disabled, for distinguishing renderer output from effect regressions. */
@NullMarked
public final class WorldControlGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    WorldPixelsGameTest.runScene(context, true);
  }
}
