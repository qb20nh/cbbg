package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.sulkan.SulkanCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;

/** Check Sulkan's startup state before enabling CBBG for the ordinary scenarios. */
@NullMarked
public final class SulkanSetupGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    if (!FabricLoader.getInstance().isModLoaded("sulkan")) return;
    boolean vulkan =
        context.computeOnClient(
            client ->
                "vulkan".equalsIgnoreCase(RenderSystem.getDevice().getDeviceInfo().backendName()));
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
    CbbgConfig.Mode saved = context.computeOnClient(client -> CbbgConfig.get().mode());
    context.runOnClient(
        client -> {
          if (SulkanCompat.isShaderPackActive() != vulkan) {
            throw new AssertionError("Unexpected Sulkan state in a fresh game directory");
          }
          if (SulkanCompat.isShaderPackActive()
              && CbbgClient.getEffectiveMode() != CbbgConfig.Mode.DISABLED) {
            throw new AssertionError("CBBG is active during Sulkan startup");
          }
        });
    CompletableFuture<?> reload =
        context.computeOnClient(
            client ->
                (CompletableFuture<?>)
                    Objects.requireNonNull(
                        SulkanGameTest.invoke(
                            "applySelection",
                            new Class<?>[] {Minecraft.class, boolean.class, String.class},
                            client,
                            false,
                            "__builtin__")));
    SulkanGameTest.await(context, reload);
    context.waitFor(client -> !SulkanCompat.isShaderPackActive(), 600);
    context.runOnClient(
        client -> {
          if (CbbgConfig.get().mode() != saved || CbbgClient.getEffectiveMode() != saved) {
            throw new AssertionError("CBBG did not retain its mode after Sulkan startup");
          }
        });
  }
}
