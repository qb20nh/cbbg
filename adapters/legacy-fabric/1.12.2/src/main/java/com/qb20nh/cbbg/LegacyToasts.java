package com.qb20nh.cbbg;

import net.minecraft.class_3260;
import net.minecraft.class_3264;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class LegacyToasts {
    private LegacyToasts() {}

    public static boolean supported() { return true; }

    public static void show(Text title, Text message) {
        // Legacy Yarn names: class_3264 is ToastManager, class_3260 is SystemToast.
        class_3264 manager = MinecraftClient.getInstance().method_14462();
        GenerationToast toast = manager.method_14493(GenerationToast.class, GenerationToast.class);
        if (toast == null) manager.method_14491(new GenerationToast(title, message));
        else toast.method_14485(title, message);
    }

    public static final class GenerationToast extends class_3260 {
        private GenerationToast(Text title, Text message) {
            super(class_3260.class_3261.TUTORIAL_HINT, title, message);
        }

        @Override
        public Object method_14487() {
            return GenerationToast.class;
        }
    }
}
