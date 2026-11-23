package com.qb20nh.cbbg.smoke;

import com.qb20nh.cbbg.LegacyNotifications;
import com.qb20nh.cbbg.LegacyToasts.GenerationToast;
import net.minecraft.class_3264;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

final class ToastParity {
    static void prepare() throws Exception {
        for (String sound : new String[] {"in", "out"}) {
            MinecraftClient.getInstance().getResourceManager()
                    .getResource(new Identifier("minecraft", "sounds/ui/toast/" + sound + ".ogg"))
                    .getInputStream().close();
        }
    }

    static int run() {
        class_3264 manager = MinecraftClient.getInstance().method_14462();
        GenerationToast before = manager.method_14493(GenerationToast.class, GenerationToast.class);
        if (before == null) throw new AssertionError("Generation toast was not queued");
        LegacyNotifications.generation(true);
        if (manager.method_14493(GenerationToast.class, GenerationToast.class) != before) {
            throw new AssertionError("Completion did not update the existing generation toast");
        }
        return 2;
    }
}
