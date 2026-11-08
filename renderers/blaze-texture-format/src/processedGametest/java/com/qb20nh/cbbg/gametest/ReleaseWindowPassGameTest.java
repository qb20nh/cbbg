package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL30;

@NullMarked
public final class ReleaseWindowPassGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseLifecycleGameTest.checkBackend(context);
    try (var world = context.worldBuilder().create()) {
      ReleaseWorldAccess.waitForChunksRender(world);
      ReleaseLifecycleGameTest.command(context, "mode set enabled");
      ReleaseLifecycleGameTest.command(context, "format set rgba16f");
      ReleaseLifecycleGameTest.awaitFormat(context, GL30.GL_RGBA16F);
      ReleaseLifecycleGameTest.awaitPresentation(context);
      ReleasePixels.check(context, false);
      ReleaseLifecycleGameTest.command(context, "mode set demo");
      ReleaseLifecycleGameTest.command(context, "format set rgba32f");
      ReleaseLifecycleGameTest.awaitFormat(context, GL30.GL_RGBA32F);
      ReleaseLifecycleGameTest.awaitPresentation(context);
      ReleasePixels.check(context, true);
      ReleaseWindowResources.check(context);
      resizeReleasesScreenshotTarget(context);
      ReleaseWindowState.check(context);
    }
  }

  private static void resizeReleasesScreenshotTarget(ClientGameTestContext context) {
    var previous =
        context.computeOnClient(
            client ->
                java.util.Objects.requireNonNull(
                    java.util.Objects.requireNonNull(ReleaseShaderFailureTarget.render(client))
                        .getColorTexture()));
    int[] original =
        context.computeOnClient(
            client -> new int[] {client.getWindow().getWidth(), client.getWindow().getHeight()});
    try {
      context.getInput().resizeWindow(854, 480);
      context.waitFor(
          client ->
              client.getMainRenderTarget().width == 854
                  && client.getMainRenderTarget().height == 480,
          600);
      ReleaseLifecycleGameTest.awaitPresentation(context);
      context.runOnClient(
          client -> {
            if (!previous.isClosed()) {
              throw new AssertionError("Window resize retained the previous screenshot texture");
            }
          });
    } finally {
      context.getInput().resizeWindow(original[0], original[1]);
      context.waitFor(
          client ->
              client.getMainRenderTarget().width == original[0]
                  && client.getMainRenderTarget().height == original[1],
          600);
    }
  }
}
