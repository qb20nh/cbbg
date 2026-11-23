package com.qb20nh.cbbg.smoke;

import net.minecraft.client.gui.screen.ChatScreen;

final class CommandRoutingParity {
    static void send(String message) {
        new ChatScreen().sendMessage(message);
    }
}
