package com.qb20nh.cbbg.gametest;

import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

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
    return new ChatScreen(initial);
  }

  static void click(AbstractWidget widget, double x, double y) {
    widget.onClick(x, y);
  }

  static void resizeWindow(ClientGameTestContext context, int width, int height) {
    context.runOnClient(
        client -> GLFW.glfwSetWindowSize(client.getWindow().getWindow(), width, height));
  }

  static void addChatMessage(Minecraft client, Component message) {
    chat(client).addMessage(message);
  }

  static Path takeScreenshot(ClientGameTestContext context, String name) {
    return context.takeScreenshot(name);
  }
}
