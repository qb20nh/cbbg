package com.qb20nh.cbbg;

import com.qb20nh.cbbg.config.CbbgConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;

public final class LegacyNotifications {
    private LegacyNotifications() {}

    public static void generation(boolean complete) {
        MinecraftClient client = MinecraftClient.getInstance();
        CbbgConfig config = CbbgConfig.get();
        String event = complete ? "complete" : "generating";
        if (config.notifyChat() && client.world != null) {
            TranslatableText message = new TranslatableText("cbbg.chat.stbn." + event);
            message.getStyle().setFormatting(complete ? Formatting.GREEN : Formatting.YELLOW);
            client.inGameHud.getChatHud().addMessage(message);
        }
        if (config.notifyToast() && LegacyToasts.supported()) {
            LegacyToasts.show(new TranslatableText("cbbg.toast.stbn.title"),
                    new TranslatableText("cbbg.toast.stbn." + event));
        }
    }
}
