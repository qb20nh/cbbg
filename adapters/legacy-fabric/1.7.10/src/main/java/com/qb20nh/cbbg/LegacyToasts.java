package com.qb20nh.cbbg;

import net.minecraft.text.Text;

public final class LegacyToasts {
    private LegacyToasts() {}

    public static boolean supported() { return false; }

    public static void show(Text title, Text message) {
        // This client has no toast manager. LegacyNotifications handles chat.
    }
}
