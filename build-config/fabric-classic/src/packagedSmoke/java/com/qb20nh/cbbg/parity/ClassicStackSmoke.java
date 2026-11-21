package com.qb20nh.cbbg.parity;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.qb20nh.cbbg.parity.mixin.ClassicChatObservation;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Checks settings, chat persistence and an ImmediatelyFast target in the combined profile. */
final class ClassicStackSmoke {
  private static final String CHAT_COMMAND = "/cbbg mode set demo";
  private static final String CHAT_MARKER = "CBBG combined-profile chat fixture";
  private final Screen settings;
  private boolean sentCommand;

  ClassicStackSmoke(Minecraft client) throws Exception {
    checkSignAtlas(client);
    settings =
        (Screen)
            Class.forName("com.terraformersmc.modmenu.ModMenu")
                .getMethod("getConfigScreen", String.class, Screen.class)
                .invoke(null, "cbbg", null);
    if (settings == null
        || !(settings.getTitle().getContents() instanceof TranslatableContents title)
        || !title.getKey().equals("cbbg.config.title")) {
      throw new AssertionError("Mod Menu did not expose CBBG settings");
    }
    client.setScreen(settings);
  }

  boolean tick(Minecraft client) throws Exception {
    if (!sentCommand) {
      if (client.screen != settings
          || settings.children().stream().noneMatch(AbstractWidget.class::isInstance)) {
        throw new AssertionError("Mod Menu's CBBG settings screen did not initialize its controls");
      }
      System.out.println("CBBG_CLASSIC_STACK_MODMENU_PASS");
      var chat = client.gui.getChat();
      chat.getRecentChat().clear();
      chat.addMessage(Component.literal(CHAT_MARKER));
      var screen = new ChatScreen("");
      client.setScreen(screen);
      screen.handleChatInput(CHAT_COMMAND, true);
      if (!Files.readString(client.gameDirectory.toPath().resolve("config/cbbg.json"))
          .contains("\"mode\": \"DEMO\"")) {
        throw new AssertionError("The chat command did not save CBBG demo mode");
      }
      sentCommand = true;
      return false;
    }
    verifyChatPersistence(client);
    client.setScreen(null);
    System.out.println("CBBG_CLASSIC_STACK_CHAT_PASS");
    return true;
  }

  private static void checkSignAtlas(Minecraft client) throws Exception {
    Class<?> type =
        Class.forName(
            "net.raphimc.immediatelyfast.feature.sign_text_buffering.SignAtlasFramebuffer");
    RenderTarget atlas = (RenderTarget) type.getConstructor().newInstance();
    ResourceLocation textureId = (ResourceLocation) type.getMethod("getTextureId").invoke(atlas);
    int framebuffer;
    int texture;
    try {
      ClassicPackagedSmoke.command(client, "cbbg mode set enabled");
      ClassicPackagedSmoke.command(client, "cbbg format set rgba32f");
      atlas.resize(31, 19, Minecraft.ON_OSX);
      checkAtlas(client, atlas, textureId);
      ClassicPackagedSmoke.command(client, "cbbg mode set disabled");
      atlas.resize(47, 23, Minecraft.ON_OSX);
      checkAtlas(client, atlas, textureId);
      framebuffer = atlas.frameBufferId;
      texture = atlas.getColorTextureId();
    } finally {
      ((AutoCloseable) atlas).close();
      client.getTextureManager().release(textureId);
      ClassicPackagedSmoke.command(client, "cbbg format set rgba16f");
      ClassicPackagedSmoke.command(client, "cbbg mode set enabled");
      client.getMainRenderTarget().bindWrite(true);
    }
    if (GL11.glIsTexture(texture) || GL30.glIsFramebuffer(framebuffer)) {
      throw new AssertionError("ImmediatelyFast's atlas leaked GPU storage");
    }
    System.out.println("CBBG_CLASSIC_STACK_IMMEDIATELYFAST_PASS");
  }

  private static void checkAtlas(Minecraft client, RenderTarget atlas, ResourceLocation textureId) {
    ClassicPackagedSmoke.requireFormat(atlas, GL11.GL_RGBA8);
    if (atlas.getDepthTextureId() != -1
        || client.getTextureManager().getTexture(textureId).getId() != atlas.getColorTextureId()) {
      throw new AssertionError(
          "ImmediatelyFast atlas lost its texture or gained an unexpected depth attachment");
    }
    int previous = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, atlas.frameBufferId);
    try {
      if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
        throw new AssertionError("ImmediatelyFast atlas framebuffer is incomplete");
      }
    } finally {
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previous);
    }
  }

  private static void verifyChatPersistence(Minecraft client) throws Exception {
    checkChat(client);
    Class<?> log = Class.forName("obro1961.chatpatches.chatlog.ChatLog");
    log.getMethod("serialize", boolean.class).invoke(null, false);
    var file = log.getDeclaredField("file");
    file.setAccessible(true);
    String saved = Files.readString((Path) file.get(null));
    if (!saved.contains(CHAT_COMMAND) || !saved.contains(CHAT_MARKER)) {
      throw new AssertionError("Chat Patches did not save the command and message");
    }
    var chat = client.gui.getChat();
    chat.clearMessages(false);
    chat.getRecentChat().clear();
    if (containsMarker(client) || !chat.getRecentChat().isEmpty()) {
      throw new AssertionError("Chat Patches fixture data did not clear");
    }
    log.getMethod("deserialize").invoke(null);
    log.getMethod("restore", Minecraft.class).invoke(null, client);
    checkChat(client);
  }

  private static void checkChat(Minecraft client) {
    if (!containsMarker(client) || !client.gui.getChat().getRecentChat().contains(CHAT_COMMAND)) {
      throw new AssertionError("Chat Patches lost the command or message");
    }
  }

  private static boolean containsMarker(Minecraft client) {
    return ((ClassicChatObservation) client.gui.getChat())
        .cbbg$messages().stream()
            .map(GuiMessage::content)
            .anyMatch(message -> message.getString().contains(CHAT_MARKER));
  }
}
