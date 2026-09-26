package com.qb20nh.cbbg.gametest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

final class ClientTestAccess {
  private ClientTestAccess() {}

  static Screen screen(Minecraft client) {
    return client.gui.screen();
  }

  static net.minecraft.client.gui.components.ChatComponent chat(Minecraft client) {
    return client.gui.hud.getChat();
  }

  static void addChatMessage(Minecraft client, net.minecraft.network.chat.Component message) {
    chat(client).addClientSystemMessage(message);
  }

  static java.nio.file.Path takeScreenshot(
      net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext context,
      String name) {
    return context.takeScreenshot(name);
  }
}
