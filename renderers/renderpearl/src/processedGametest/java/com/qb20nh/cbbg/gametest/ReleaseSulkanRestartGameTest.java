package com.qb20nh.cbbg.gametest;

import com.mojang.renderpearl.api.GpuFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Prepare, verify and disabled control run in separate JVMs with a shared game directory. */
public final class ReleaseSulkanRestartGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    run(context, "__builtin__");
  }

  static void run(ClientGameTestContext context, String pack) {
    String phase = System.getProperty("cbbg.test.restart");
    if (!"prepare".equals(phase) && !"verify".equals(phase) && !"control".equals(phase)) {
      throw new AssertionError("Sulkan restart requires prepare, verify or control");
    }
    ReleaseSulkanGameTest.requireVulkan(context);
    // Observe persisted preferences and the initial gate before any command or selection change.
    context.runOnClient(
        client -> {
          ReleaseSulkanGameTest.checkGate(
              client, ReleaseSulkanGameTest.userMode(client), ReleaseSulkanGameTest.active());
          if (phase.equals("verify")) {
            Object config = ReleaseSulkanGameTest.invoke("config", new Class<?>[0]);
            try {
              if (!(Boolean) config.getClass().getMethod("enabled").invoke(config)
                  || !pack.equals(config.getClass().getMethod("selectedPackId").invoke(config))) {
                throw new AssertionError("Sulkan selection did not survive restart");
              }
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError("Could not read Sulkan settings", failure);
            }
            var saved = ReleaseClient.settings();
            if (!saved.get("mode").getAsString().equals("DEMO")
                || !saved.get("pixelFormat").getAsString().equals("RGBA16F")) {
              throw new AssertionError("CBBG settings did not survive restart");
            }
            ReleaseSulkanGameTest.checkGate(client, "DEMO", true);
          } else if (!pack.equals("__builtin__")) {
            ReleaseSulkanExternalGameTest.install(client);
          }
        });
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      if (!phase.equals("verify")) {
        var original = ReleaseSulkanGameTest.saveSettings(context);
        try {
          Path evidence = Path.of(System.getProperty("cbbg.test.evidence"));
          Files.createDirectories(evidence);
          Files.writeString(
              evidence.resolve("sulkan-restart-original-settings.json"), original + "\n");
        } catch (java.io.IOException failure) {
          throw new AssertionError("Could not retain pre-restart preferences", failure);
        }
        ReleaseClient.command(
            context, "mode set " + (phase.equals("control") ? "disabled" : "demo"));
        ReleaseClient.command(context, "format set rgba16f");
        ReleaseSulkanGameTest.select(context, true, pack);
      }
      context.waitFor(client -> ReleaseSulkanGameTest.active(), 600);
      ReleaseClient.awaitFormat(context, GpuFormat.RGBA8_UNORM);
      String user = phase.equals("control") ? "DISABLED" : "DEMO";
      ReleaseSulkanGameTest.assertStopped(context, user);
      long stoppedDraws = ProcessedRenderObservations.draws();
      long stoppedPresentations = ProcessedRenderObservations.presentations();
      if (!pack.equals("__builtin__")) {
        context.waitFor(client -> client.gui.overlay() == null, 600);
        context.waitTicks(5);
        ReleaseSulkanExternalGameTest.capture(context, "restart-" + phase, true);
      }
      if (phase.equals("verify")) {
        ReleaseSulkanGameTest.select(context, false, pack);
        context.waitFor(client -> !ReleaseSulkanGameTest.active(), 600);
        ReleaseClient.awaitFormat(context, GpuFormat.RGBA16_FLOAT);
        ReleaseSulkanGameTest.awaitRendering(context, "DEMO");
        if (ProcessedRenderObservations.draws() <= stoppedDraws
            || ProcessedRenderObservations.presentations() <= stoppedPresentations) {
          throw new AssertionError(
              "Disabling Sulkan after restart did not restore CBBG presentation");
        }
        if (!pack.equals("__builtin__")) {
          ReleaseSulkanExternalGameTest.capture(context, "restart-disabled", false);
        }
      }
    }
    // Prepare intentionally keeps both settings and the shader selection for the next JVM.
  }
}
