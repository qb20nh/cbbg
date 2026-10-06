package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;

/** Checks packaged world precision and dithering behind glass with both transparency options. */
@NullMarked
public final class ReleaseTransparencyGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    Object previous = context.computeOnClient(ReleaseTransparencySettings::snapshot);
    try {
      for (boolean enabled : new boolean[] {false, true}) {
        context.runOnClient(client -> ReleaseTransparencySettings.set(client, enabled));
        ReleaseWorldPixelsGameTest.runScene(context, true);
      }
    } finally {
      context.runOnClient(client -> ReleaseTransparencySettings.restore(client, previous));
    }
  }
}
