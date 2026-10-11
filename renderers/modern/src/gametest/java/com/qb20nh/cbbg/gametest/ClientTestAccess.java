package com.qb20nh.cbbg.gametest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class ClientTestAccess {
  private ClientTestAccess() {}

  static @Nullable Screen screen(Minecraft client) {
    return client.gui.screen();
  }

  static net.minecraft.client.gui.components.ChatComponent chat(Minecraft client) {
    return client.gui.hud.getChat();
  }

  static ChatScreen chatScreen(String initial) {
    return new ChatScreen(initial, false);
  }

  static void click(AbstractWidget widget, double x, double y) {
    widget.onClick(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
  }

  static void resizeWindow(
      net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext context,
      int width,
      int height) {
    context.getInput().resizeWindow(width, height);
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
