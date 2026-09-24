package com.qb20nh.cbbg.gametest;

import com.qb20nh.cbbg.config.CbbgConfig;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

/** Presence checks complement the renderer scenarios run with these same mods loaded. */
public final class OptionalModsGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        List<String> mods = List.of(System.getProperty("cbbg.test.compat", "none").split("\\+"));
        requirePresence("sodium", mods.contains("sodium") || mods.contains("iris"));
        requirePresence("iris", mods.contains("iris"));
        requirePresence("chatpatches", mods.contains("chatpatches"));
        requirePresence("immediatelyfast", mods.contains("immediatelyfast"));
        if (!mods.contains("chatpatches")) return;

        CbbgConfig.Mode original = CbbgConfig.get().mode();
        String marker = "CBBG Chat Patches regression fixture";
        String command = "/cbbg mode set demo";
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null, 600);
            context.runOnClient(client -> {
                CbbgConfig.setMode(CbbgConfig.Mode.ENABLED);
                ClientTestAccess.addChatMessage(client, Component.literal(marker));
            });
            context.setScreen(() -> new ChatScreen("", false));
            context.waitForScreen(ChatScreen.class);
            context.runOnClient(client -> ((ChatScreen) ClientTestAccess.screen(client))
                    .handleChatInput(command, true));
            context.waitFor(client -> CbbgConfig.get().mode() == CbbgConfig.Mode.DEMO, 200);
            context.setScreen(() -> new ChatScreen("", false));
            context.waitTick();
            context.runOnClient(client -> {
                ChatComponent chat = ClientTestAccess.chat(client);
                if (!chat.getRecentChat().contains(command) || !contains(chat, marker)) {
                    throw new AssertionError("Chat Patches lost command history or CBBG chat content");
                }
            });
            ClientTestAccess.takeScreenshot(context, "cbbg-chatpatches-chat");
        } finally {
            context.runOnClient(client -> CbbgConfig.setMode(original));
        }
    }

    private static void requirePresence(String mod, boolean expected) {
        if (FabricLoader.getInstance().isModLoaded(mod) != expected) {
            throw new AssertionError(mod + " presence does not match the requested fixture");
        }
    }

    private static boolean contains(ChatComponent chat, String marker) {
        try {
            var field = ChatComponent.class.getDeclaredField("allMessages");
            field.setAccessible(true);
            for (Object value : (List<?>) field.get(chat)) {
                Component content = (Component) value.getClass().getMethod("content").invoke(value);
                if (content.getString().contains(marker)) return true;
            }
            return false;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not inspect chat history", failure);
        }
    }
}
