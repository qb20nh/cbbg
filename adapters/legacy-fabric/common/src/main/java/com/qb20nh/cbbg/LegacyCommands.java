package com.qb20nh.cbbg;

import com.qb20nh.cbbg.render.LegacyDither;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;

/** Legacy Fabric chat services for the shared pre-Brigadier grammar. */
public final class LegacyCommands {
    private LegacyCommands() {}

    public static List<String> suggest(String message) {
        return LegacyCommandGrammar.suggest(message, LegacyToasts.supported());
    }

    public static boolean execute(String message) {
        return execute(message, text -> MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(text));
    }

    public static boolean execute(String message, Consumer<Text> feedback) {
        return execute(message, feedback, LegacyDither::regenerate, LegacyDither::reload);
    }

    public static boolean execute(String message, Consumer<Text> feedback, Runnable regenerate, Runnable reload) {
        return LegacyCommandGrammar.execute(message, LegacyToasts.supported(), new LegacyCommandGrammar.Feedback() {
            public Object translate(String key) { return new TranslatableText(key); }
            public void send(boolean error, String key, Object... args) {
                Text text = new TranslatableText(key, args);
                if (error) text.getStyle().setFormatting(Formatting.RED);
                feedback.accept(text);
            }
        }, regenerate, reload);
    }
}
