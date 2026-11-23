package com.qb20nh.cbbg.smoke;

import com.qb20nh.cbbg.LegacyNotifications;
import com.qb20nh.cbbg.config.CbbgConfig;

public final class LegacyNotificationParity {
    public static int started;
    public static int completed;
    public static int toasts;

    static int run() throws Exception {
        ToastParity.prepare();
        boolean chat = CbbgConfig.get().notifyChat();
        boolean toast = CbbgConfig.get().notifyToast();
        try {
            CbbgConfig.setNotifyChat(false);
            CbbgConfig.setNotifyToast(false);
            LegacyNotifications.generation(false);
            LegacyNotifications.generation(true);
            if (toasts != 0) throw new AssertionError("Disabled toast notification was dispatched");
            CbbgConfig.setNotifyToast(true);
            LegacyNotifications.generation(false);
            if (toasts != 1) throw new AssertionError("Enabled toast notification was not dispatched");
            return 2 + ToastParity.run();
        } finally {
            CbbgConfig.setNotifyChat(chat);
            CbbgConfig.setNotifyToast(toast);
            started = completed = toasts = 0;
        }
    }
}
