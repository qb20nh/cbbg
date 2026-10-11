package com.qb20nh.cbbg.gametest;

import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ReleaseModMenuGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    boolean expected =
        List.of(Objects.requireNonNull(System.getProperty("cbbg.test.compat", "none")).split("\\+"))
            .contains("modmenu");
    if (FabricLoader.getInstance().isModLoaded("modmenu") != expected) {
      throw new AssertionError("Mod Menu presence differs from the selected profile");
    }
    if (expected) Installed.run(context);
  }

  private static final class Installed {
    @SuppressWarnings("ReferenceEquality") // Navigation must return to the same parent screen.
    static void run(ClientGameTestContext context) {
      var parent = context.computeOnClient(client -> new TitleScreen());
      var mods =
          context.computeOnClient(client -> new com.terraformersmc.modmenu.gui.ModsScreen(parent));
      context.setScreen(() -> mods);
      context.waitTick();
      context.runOnClient(
          client -> {
            if (!mods.getModHasConfigScreen("cbbg"))
              throw new AssertionError("Missing CBBG Mod Menu entry");
            mods.safelyOpenConfigScreen("cbbg");
          });
      context.waitFor(
          client ->
              client.screen != null
                  && client.screen.getTitle().equals(Component.translatable("cbbg.config.title")),
          200);
      ReleaseScreenshots.capture(context, "cbbg-modmenu-config", image -> {});
      context.clickScreenButton("cbbg.config.button.done");
      context.runOnClient(
          client -> {
            if (client.screen != mods || !mods.modScreenErrors.isEmpty()) {
              throw new AssertionError("CBBG settings did not return to Mod Menu");
            }
            mods.onClose();
            if (client.screen != parent)
              throw new AssertionError("Mod Menu did not return to its parent");
          });
    }
  }
}
