package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;

/** Checks world precision and dithering behind stained glass with both transparency modes. */
@NullMarked
public final class ReleaseTransparencyGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseClient.checkArtifactAndBackend(context);
    boolean previous = context.computeOnClient(client -> client.options.improvedTransparency().get());
    try {
      for (boolean enabled : new boolean[] {false, true}) {
        context.runOnClient(client -> client.options.improvedTransparency().set(enabled));
        ReleaseWorldPixelsGameTest.runScene(context, false, true);
      }
    } finally {
      context.runOnClient(client -> client.options.improvedTransparency().set(previous));
    }
  }
}
