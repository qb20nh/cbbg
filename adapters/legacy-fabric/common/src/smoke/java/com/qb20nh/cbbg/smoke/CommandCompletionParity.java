package com.qb20nh.cbbg.smoke;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;

/** Exercises vanilla insertion/cycling without a player or server connection. */
final class CommandCompletionParity extends ChatScreen {
    static String complete(String message, int tabs) {
        CommandCompletionParity screen = new CommandCompletionParity();
        screen.init(MinecraftClient.getInstance(), 320, 240);
        screen.chatField.setText(message);
        for (int i = 0; i < tabs; i++) screen.keyPressed('\t', 15);
        return screen.chatField.getText();
    }
}
