package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class ClientTestAccess {
  private ClientTestAccess() {}

  static @Nullable Screen screen(Minecraft client) {
    return client.screen;
  }

  static ChatComponent chat(Minecraft client) {
    return client.gui.getChat();
  }

  static ChatScreen chatScreen(String initial) {
    return new ChatScreen(initial, false);
  }

  static void click(AbstractWidget widget, double x, double y) {
    widget.onClick(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
  }

  static void resizeWindow(ClientGameTestContext context, int width, int height) {
    context.getInput().resizeWindow(width, height);
  }

  static void addChatMessage(Minecraft client, Component message) {
    chat(client).addClientSystemMessage(message);
  }

  static java.nio.file.Path takeScreenshot(ClientGameTestContext context, String name) {
    return context.takeScreenshot(name);
  }
}
