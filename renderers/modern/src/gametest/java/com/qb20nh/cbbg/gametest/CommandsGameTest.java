package com.qb20nh.cbbg.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.stbn.STBNCache;
import java.lang.reflect.Proxy;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

public final class CommandsGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        CbbgConfig original = CbbgConfig.get();
        // Commands only use feedback/error methods; fail if that contract changes.
        FabricClientCommandSource source = (FabricClientCommandSource) Proxy.newProxyInstance(
                FabricClientCommandSource.class.getClassLoader(),
                new Class<?>[] {FabricClientCommandSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendFeedback") || method.getName().equals("sendError")) {
                        return null;
                    }
                    throw new AssertionError("Unexpected command source call: " + method);
                });
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            context.runOnClient(client -> {
                execute(source, "help", 1);
                execute(source, "mode set demo", 1);
                execute(source, "mode set unknown", 0);
                if (CbbgConfig.get().mode() != CbbgConfig.Mode.DEMO) {
                    throw new AssertionError("Invalid mode changed settings");
                }
                execute(source, "format set rgba32f", 1);
                execute(source, "format set rgba8", 0);
                execute(source, "format set unknown", 0);
                if (CbbgConfig.get().pixelFormat() != CbbgConfig.PixelFormat.RGBA32F) {
                    throw new AssertionError("Invalid format changed settings");
                }
                execute(source, "stbn size 16", 1);
                execute(source, "stbn size 17", 0);
                execute(source, "stbn depth 8", 1);
                execute(source, "stbn depth 9", 0);
                execute(source, "stbn seed 42", 1);
                execute(source, "notification chat false", 1);
                execute(source, "notification toast false", 1);
                CbbgConfig settings = CbbgConfig.get();
                if (settings.stbnSize() != 16 || settings.stbnDepth() != 8
                        || settings.stbnSeed() != 42 || settings.notifyChat() || settings.notifyToast()) {
                    throw new AssertionError("Command settings were not applied");
                }
                execute(source, "stbn generate", 1);
                if (DitherController.isReady()) {
                    throw new AssertionError("Forced generation retained the old noise resource");
                }
            });
            context.waitFor(client -> DitherController.isReady(), 600);
            context.runOnClient(client -> {
                if (!client.gameRenderer.mainRenderTarget().getColorTexture().getFormat()
                        .name().equals("RGBA32_FLOAT")) {
                    throw new AssertionError("Format command did not recreate the main attachment");
                }
                execute(source, "mode set disabled", 1);
            });
            context.waitTicks(3);
            context.runOnClient(client -> {
                if (DitherController.isReady() || !client.gameRenderer.mainRenderTarget()
                        .getColorTexture().getFormat().name().equals("RGBA8_UNORM")) {
                    throw new AssertionError("Disable command did not restore the vanilla target");
                }
                execute(source, "stbn generate", 1);
            });
            context.waitFor(client -> STBNCache.isCacheValid(16, 16, 8), 600);
            context.runOnClient(client -> {
                if (DitherController.isReady() || CbbgConfig.get().mode() != CbbgConfig.Mode.DISABLED) {
                    throw new AssertionError("Generating a cache while disabled activated the effect");
                }
                execute(source, "mode set enabled", 1);
            });
            context.waitFor(client -> DitherController.isReady(), 600);
        } finally {
            context.runOnClient(client -> {
                CbbgConfig.setMode(original.mode());
                CbbgConfig.setPixelFormat(original.pixelFormat());
                CbbgConfig.setStbnSize(original.stbnSize());
                CbbgConfig.setStbnDepth(original.stbnDepth());
                CbbgConfig.setStbnSeed(original.stbnSeed());
                CbbgConfig.setNotifyChat(original.notifyChat());
                CbbgConfig.setNotifyToast(original.notifyToast());
                DitherController.resetAfterToggle();
            });
        }
    }

    private static void execute(FabricClientCommandSource source, String command, int expected) {
        var dispatcher = ClientCommands.getActiveDispatcher();
        if (dispatcher == null) {
            throw new AssertionError("No active client command dispatcher");
        }
        try {
            if (dispatcher.execute("cbbg " + command, source) != expected) {
                throw new AssertionError("Unexpected command result: " + command);
            }
        } catch (CommandSyntaxException failure) {
            throw new AssertionError("Command failed: " + command, failure);
        }
    }
}
