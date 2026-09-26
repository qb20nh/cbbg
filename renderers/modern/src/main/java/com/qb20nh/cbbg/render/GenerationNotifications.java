package com.qb20nh.cbbg.render;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.platform.Text;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

/** Client-thread notifications for explicitly requested noise generation. */
public final class GenerationNotifications {
  private static CompletableFuture<?> pending;

  private GenerationNotifications() {}

  public static void started(CompletableFuture<?> generation) {
    pending = generation;
    notify("generating", ChatFormatting.YELLOW);
  }

  public static void follow(CompletableFuture<?> generation) {
    if (pending != null) {
      pending = generation;
    }
  }

  public static void tick() {
    if (pending == null || !pending.isDone()) {
      return;
    }
    boolean succeeded = !pending.isCompletedExceptionally() && !pending.isCancelled();
    pending = null;
    if (succeeded) {
      notify("complete", ChatFormatting.GREEN);
    }
  }

  public static void onWorldJoin() {
    if (pending != null) {
      // Preserve the original adapter's world-join reminder.
      NotificationPlatform.chat(
          Text.translatable("cbbg.chat.stbn.generating").withStyle(ChatFormatting.YELLOW));
    }
  }

  public static void close() {
    pending = null;
  }

  private static void notify(String state, ChatFormatting color) {
    Minecraft client = Minecraft.getInstance();
    CbbgConfig settings = CbbgConfig.get();
    if (settings.notifyChat() && client.level != null) {
      NotificationPlatform.chat(Text.translatable("cbbg.chat.stbn." + state).withStyle(color));
    }
    if (settings.notifyToast()) {
      NotificationPlatform.toast(
          Text.translatable("cbbg.toast.stbn.title"),
          Text.translatable("cbbg.toast.stbn." + state));
    }
  }
}
