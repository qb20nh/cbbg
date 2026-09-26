package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/** Checks the unmodified startup gate before ordinary packaged-mod scenarios. */
public final class ReleaseSulkanSetupGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    if (!FabricLoader.getInstance().isModLoaded("sulkan")) return;
    ReleaseClient.checkArtifactAndBackend(context);
    boolean vulkan = ReleaseSulkanGameTest.vulkan(context);
    if (!vulkan) {
      context.waitFor(
          client ->
              client.gui.screen() instanceof ConfirmScreen
                  && client
                      .gui
                      .screen()
                      .getTitle()
                      .equals(Component.translatable("sulkan.backend.title")),
          200);
      context.clickScreenButton("sulkan.backend.continue");
      context.waitFor(client -> client.gui.screen() instanceof TitleScreen, 200);
    }
    String user = context.computeOnClient(ReleaseSulkanGameTest::userMode);
    context.runOnClient(
        client -> {
          if (ReleaseSulkanGameTest.active() != vulkan) {
            throw new AssertionError("Unexpected Sulkan state in a fresh game directory");
          }
          ReleaseSulkanGameTest.checkGate(client, user, vulkan);
        });
    if (vulkan) ReleaseSulkanGameTest.assertStopped(context, user);
    ReleaseSulkanGameTest.select(context, false, "__builtin__");
    context.waitFor(client -> !ReleaseSulkanGameTest.active(), 600);
    context.runOnClient(client -> ReleaseSulkanGameTest.checkGate(client, user, false));
  }
}
