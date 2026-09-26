package com.qb20nh.cbbg.gametest;

import static com.qb20nh.cbbg.gametest.IrisFixture.*;

import com.mojang.renderpearl.api.GpuFormat;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;

/** Prepare and verify run in separate JVMs with the same game directory. */
@NullMarked
public final class ReleaseIrisRestartGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    String phase = System.getProperty("cbbg.test.restart");
    if (phase == null
        || (!"prepare".equals(phase) && !"verify".equals(phase) && !"control".equals(phase))) {
      throw new AssertionError("Restart test requires prepare, verify or disabled control phase");
    }
    ReleaseIrisGameTest.requireIris(context);
    ReleaseClient.checkArtifactAndBackend(context);
    if (phase.equals("verify")) {
      // Check startup preferences before any command or shader configuration change.
      context.runOnClient(
          client -> {
            Object config = Objects.requireNonNull(iris("getIrisConfig"));
            try {
              if (!Objects.requireNonNull(
                      (Boolean)
                          Objects.requireNonNull(config)
                              .getClass()
                              .getMethod("areShadersEnabled")
                              .invoke(Objects.requireNonNull(config)))
                  || !Optional.of("cbbg-parity")
                      .equals(config.getClass().getMethod("getShaderPackName").invoke(config))) {
                throw new AssertionError("Iris shader selection did not survive restart");
              }
            } catch (ReflectiveOperationException failure) {
              throw new LinkageError("Could not inspect persisted Iris settings", failure);
            }
            var saved = ReleaseClient.settings();
            if (!saved.get("mode").getAsString().equals("ENABLED")
                || !saved.get("pixelFormat").getAsString().equals("RGBA16F")) {
              throw new AssertionError("CBBG preferences did not survive restart");
            }
          });
    } else {
      installPack();
    }
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      if (!phase.equals("verify")) {
        ReleaseClient.command(
            context, "mode set " + (phase.equals("control") ? "disabled" : "enabled"));
        ReleaseClient.command(context, "format set rgba16f");
        context.runOnClient(
            client -> {
              invoke(
                  Objects.requireNonNull(iris("getIrisConfig")),
                  "setShaderPackName",
                  String.class,
                  "cbbg-parity");
              ReleaseIrisGameTest.setShaders(true);
            });
      }
      context.waitFor(client -> ReleaseIrisGameTest.active(), 600);
      ReleaseClient.awaitFormat(context, GpuFormat.RGBA8_UNORM);
      long stopped = ProcessedRenderObservations.draws();
      context.waitTicks(5);
      context.runOnClient(
          client -> {
            if (Objects.requireNonNull((Boolean) iris("isFallback"))
                || ProcessedRenderObservations.draws() != stopped) {
              throw new AssertionError("Restart shader did not suspend CBBG cleanly");
            }
            ReleaseIrisGameTest.checkDebug(
                client, phase.equals("control") ? "DISABLED" : "ENABLED", true);
          });
      ReleaseIrisGameTest.captureShader(context, "iris-restart-" + phase);
      if (phase.equals("verify")) {
        context.runOnClient(client -> ReleaseIrisGameTest.setShaders(false));
        context.waitFor(client -> !ReleaseIrisGameTest.active(), 600);
        ReleaseClient.awaitFormat(context, GpuFormat.RGBA16_FLOAT);
        ReleaseClient.awaitDrawAfter(context, stopped);
        context.runOnClient(client -> ReleaseIrisGameTest.checkDebug(client, "ENABLED", false));
      }
    }
    // Prepare keeps both preferences and shader selection for the next JVM.
  }
}
