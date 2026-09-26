package com.qb20nh.cbbg.gametest;

import com.mojang.renderpearl.api.GpuFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Confirms the external native pack reaches pixels and suspends packaged CBBG. */
public final class ReleaseSulkanExternalGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseSulkanGameTest.requireVulkan(context);
    Object original =
        context.computeOnClient(client -> ReleaseSulkanGameTest.invoke("config", new Class<?>[0]));
    context.runOnClient(
        client -> {
          ReleaseSulkanGameTest.checkGate(
              client, ReleaseSulkanGameTest.userMode(client), ReleaseSulkanGameTest.active());
          install(client);
        });
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      var saved = ReleaseSulkanGameTest.saveSettings(context);
      try {
        ReleaseClient.command(context, "mode set demo");
        for (int reload = 0; reload < 2; reload++) {
          ReleaseSulkanGameTest.select(context, true, "cbbg-native-test");
          context.waitFor(client -> ReleaseSulkanGameTest.active(), 600);
          ReleaseClient.awaitFormat(context, GpuFormat.RGBA8_UNORM);
          ReleaseSulkanGameTest.assertStopped(context, "DEMO");
          capture(context, "external-" + reload, true);
        }
        ReleaseSulkanGameTest.select(context, false, "cbbg-native-test");
        context.waitFor(client -> !ReleaseSulkanGameTest.active(), 600);
        ReleaseSulkanGameTest.awaitRendering(context, "DEMO");
        capture(context, "disabled", false);
      } finally {
        try {
          ReleaseSulkanGameTest.restoreConfig(context, original);
        } finally {
          ReleaseSulkanGameTest.restoreSettings(context, saved);
        }
      }
    }
  }

  static void install(Minecraft client) {
    Path pack = client.gameDirectory.toPath().resolve("shaders/cbbg-native-test");
    try {
      Files.createDirectories(pack);
      for (String name : new String[] {"sulkan.json", "color.fsh"}) {
        try (var input =
            ReleaseSulkanExternalGameTest.class.getResourceAsStream("/sulkan-fixture/" + name)) {
          if (input == null) throw new AssertionError("Missing native pack fixture: " + name);
          Files.copy(input, pack.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
      }
    } catch (java.io.IOException failure) {
      throw new AssertionError("Could not install native test pack", failure);
    }
    ReleaseSulkanGameTest.invoke("reloadPacks", new Class<?>[0]);
  }

  static void capture(ClientGameTestContext context, String name, boolean shader) {
    CompletableFuture<Void> capture = new CompletableFuture<>();
    context.runOnClient(
        client ->
            Screenshot.takeScreenshot(
                client.gameRenderer.mainRenderTarget(),
                image -> {
                  try (image) {
                    int magenta = 0;
                    int total = 0;
                    for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
                      for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                        total++;
                        int pixel = image.getPixel(x, y);
                        int red = (pixel >>> 16) & 255;
                        int green = (pixel >>> 8) & 255;
                        int blue = pixel & 255;
                        // Minecraft's vignette darkens shader output after presentation.
                        if (red >= 128 && red == blue && green == 0) magenta++;
                      }
                    }
                    Path evidence = Path.of(System.getProperty("cbbg.test.evidence"));
                    Files.createDirectories(evidence);
                    image.writeToFile(evidence.resolve("sulkan-" + name + ".png"));
                    if (total == 0 || (magenta > total * 0.9) != shader) {
                      throw new AssertionError(
                          "External Sulkan pack screenshot does not match shader state");
                    }
                    capture.complete(null);
                  } catch (Throwable failure) {
                    capture.completeExceptionally(failure);
                  }
                }));
    context.waitFor(client -> capture.isDone(), 200);
    capture.join();
  }
}
