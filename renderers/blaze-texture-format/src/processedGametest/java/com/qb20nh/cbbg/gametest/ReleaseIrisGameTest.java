package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

@NullMarked
public final class ReleaseIrisGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    boolean expected =
        List.of(Objects.requireNonNull(System.getProperty("cbbg.test.compat", "none")).split("\\+"))
            .contains("iris");
    if (FabricLoader.getInstance().isModLoaded("iris") != expected) {
      throw new AssertionError("Iris presence differs from the selected profile");
    }
    if (!expected) return;
    boolean renderScale = ReleaseRenderScale.selected();
    IrisFixture.installPack();
    float originalScale =
        renderScale
            ? context.computeOnClient(client -> RenderScaleTestAccess.setShaderTestScale(0.5f))
            : 1.0f;
    try (var world = context.worldBuilder().create()) {
      ReleaseWorldAccess.waitForChunksRender(world);
      ReleaseLifecycleGameTest.command(context, "mode set enabled");
      ReleaseLifecycleGameTest.command(context, "format set rgba16f");
      ReleaseLifecycleGameTest.awaitFormat(context, GL30.GL_RGBA16F);
      ReleaseLifecycleGameTest.awaitPresentation(context);
      if (renderScale) ReleaseRenderScale.awaitHalfScale(context, GL30.GL_RGBA16F);
      context.runOnClient(
          client -> {
            IrisFixture.invoke(
                Objects.requireNonNull(IrisFixture.iris("getIrisConfig")),
                "setShaderPackName",
                String.class,
                "cbbg-parity");
            shaders(true);
          });
      try {
        context.waitFor(client -> active(), 600);
        ReleaseLifecycleGameTest.awaitFormat(context, GL11.GL_RGBA8);
        if (renderScale) ReleaseRenderScale.awaitHalfScale(context, GL11.GL_RGBA8);
        ReleaseLifecycleGameTest.noPresentations(context);
        context.runOnClient(
            client -> {
              if (Boolean.TRUE.equals(IrisFixture.iris("isFallback"))) {
                throw new AssertionError("Iris did not render the fixture shaderpack");
              }
            });
        ReleaseScreenshots.capture(context, "iris-active", ReleaseIrisGameTest::assertShader);
        ReleaseLifecycleGameTest.command(context, "mode set demo");
        ReleaseLifecycleGameTest.noPresentations(context);
      } finally {
        context.runOnClient(client -> shaders(false));
      }
      context.waitFor(client -> !active(), 600);
      ReleaseLifecycleGameTest.awaitFormat(context, GL30.GL_RGBA16F);
      ReleaseLifecycleGameTest.awaitPresentation(context);
      if (renderScale) ReleaseRenderScale.awaitHalfScale(context, GL30.GL_RGBA16F);
    } finally {
      if (renderScale) {
        context.runOnClient(client -> RenderScaleTestAccess.setShaderTestScale(originalScale));
      }
    }
  }

  private static void shaders(boolean enabled) {
    IrisFixture.invoke(
        Objects.requireNonNull(IrisFixture.iris("getIrisConfig")),
        "setShadersEnabled",
        boolean.class,
        enabled);
    IrisFixture.saveAndReload();
  }

  private static void assertShader(NativeImage image) {
    // Chat history and notification toasts remain visible with shaderpacks active.
    IrisFixture.assertFinalShader(
        image,
        "The Iris final shader did not reach the world screenshot",
        image.getHeight() / 5,
        image.getHeight() / 2);
  }

  private static boolean active() {
    try {
      Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
      return Boolean.TRUE.equals(
          api.getMethod("isShaderPackInUse").invoke(api.getMethod("getInstance").invoke(null)));
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot read Iris shaderpack state", failure);
    }
  }
}
