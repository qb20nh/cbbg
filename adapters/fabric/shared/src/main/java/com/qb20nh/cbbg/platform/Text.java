package com.qb20nh.cbbg.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Native text construction shared by commands, settings and notifications. */
public final class Text {
    private Text() {}

    public static MutableComponent translatable(String key, Object... args) {
        return Component.translatable(key, args);
    }

    public static MutableComponent empty() { return Component.empty(); }
    public static MutableComponent literal(String text) { return Component.literal(text); }
}
