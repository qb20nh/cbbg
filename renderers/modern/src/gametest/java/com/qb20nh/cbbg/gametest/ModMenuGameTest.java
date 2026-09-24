package com.qb20nh.cbbg.gametest;

import com.qb20nh.cbbg.config.gui.CbbgConfigScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;

public final class ModMenuGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        boolean expected = java.util.List.of(System.getProperty("cbbg.test.compat", "none").split("\\+"))
                .contains("modmenu");
        boolean installed = FabricLoader.getInstance().isModLoaded("modmenu");
        if (installed != expected) {
            throw new AssertionError("Mod Menu presence does not match the requested fixture");
        }
        if (installed) {
            String version = FabricLoader.getInstance().getModContainer("modmenu").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            if (!version.equals(System.getProperty("cbbg.test.modmenu.version"))) {
                throw new AssertionError("Unexpected Mod Menu version: " + version);
            }
            Installed.run(context);
        }
    }

    // Keep optional API types out of the class loaded by the no-Mod-Menu fixture.
    private static final class Installed {
        static void run(ClientGameTestContext context) {
            var parent = context.computeOnClient(client -> new TitleScreen());
            var mods = context.computeOnClient(client ->
                    new com.terraformersmc.modmenu.gui.ModsScreen(parent));
            context.setScreen(() -> mods);
            context.waitTick();
            context.runOnClient(client -> {
                if (!com.terraformersmc.modmenu.ModMenu.hasConfigScreen("cbbg")
                        || !mods.getModHasConfigScreen("cbbg")) {
                    throw new AssertionError("Mod Menu did not discover CBBG's entry point");
                }
                mods.safelyOpenConfigScreen("cbbg");
            });
            context.waitForScreen(CbbgConfigScreen.class);
            Path screenshot = context.takeScreenshot("cbbg-modmenu-config");
            try {
                Path evidence = Path.of(System.getProperty("cbbg.test.evidence"));
                Files.createDirectories(evidence);
                Files.copy(screenshot, evidence.resolve("modmenu-config-"
                        + System.getProperty("cbbg.test.backend") + ".png"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException failure) {
                throw new AssertionError("Could not retain Mod Menu screenshot", failure);
            }
            context.clickScreenButton("cbbg.config.button.done");
            context.runOnClient(client -> {
                if (ClientTestAccess.screen(client) != mods || !mods.modScreenErrors.isEmpty()) {
                    throw new AssertionError("Config did not return cleanly to Mod Menu");
                }
                mods.onClose();
                if (ClientTestAccess.screen(client) != parent) {
                    throw new AssertionError("Mod Menu did not return to its parent screen");
                }
            });
        }
    }
}
