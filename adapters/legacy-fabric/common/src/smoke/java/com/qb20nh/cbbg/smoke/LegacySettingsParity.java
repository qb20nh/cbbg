package com.qb20nh.cbbg.smoke;

import com.qb20nh.cbbg.LegacyToasts;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.gui.CbbgConfigScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

final class LegacySettingsParity extends CbbgConfigScreen {
    private int checks;

    private LegacySettingsParity(Screen parent) { super(parent); }

    static int failedRenderer() {
        MinecraftClient client = MinecraftClient.getInstance();
        LegacySettingsParity test = new LegacySettingsParity(client.currentScreen);
        CbbgConfig before = CbbgConfig.get();
        try {
            test.init(client, 400, 300);
            for (Object value : test.buttons) {
                ButtonWidget button = (ButtonWidget) value;
                test.require(button.active == (button.id == 8), "Render failure did not lock settings");
            }
            test.click(0);
            test.seed("123");
            test.require(before.equals(CbbgConfig.get()), "Read-only settings mutated configuration");
            return test.checks;
        } finally {
            test.removed();
        }
    }

    static int run() {
        MinecraftClient client = MinecraftClient.getInstance();
        Screen parent = client.currentScreen;
        CbbgConfig saved = CbbgConfig.get();
        LegacySettingsParity test = new LegacySettingsParity(parent);
        try {
            test.init(client, 400, 300);
            test.verify(parent);
            return test.checks;
        } finally {
            test.removed();
            client.setScreen(parent);
            CbbgConfig.setMode(saved.mode());
            CbbgConfig.setPixelFormat(saved.pixelFormat());
            CbbgConfig.setStrength(saved.strength());
            CbbgConfig.setStbnSize(saved.stbnSize());
            CbbgConfig.setStbnDepth(saved.stbnDepth());
            CbbgConfig.setStbnSeed(saved.stbnSeed());
            CbbgConfig.setNotifyChat(saved.notifyChat());
            CbbgConfig.setNotifyToast(saved.notifyToast());
        }
    }

    private ButtonWidget button(int id) {
        for (Object value : buttons) if (((ButtonWidget) value).id == id) return (ButtonWidget) value;
        return null;
    }

    private void click(int id) { buttonClicked(button(id)); }

    private void slider(int id, boolean maximum) {
        ButtonWidget button = button(id);
        mouseClicked(button.x + (maximum ? button.getWidth() - 1 : 1), button.y + 10, 0);
        mouseReleased(button.x, button.y, 0);
    }

    private void seed(String value) {
        // Seed control is one row below the size/depth sliders.
        ButtonWidget size = button(3);
        mouseClicked(size.x + 50, size.y + 34, 0);
        keyPressed('\0', 207); // End
        for (int i = 0; i < 32; i++) keyPressed('\b', 14);
        for (char c : value.toCharArray()) keyPressed(c, 0);
    }

    private void verify(Screen parent) {
        require((button(7) != null) == LegacyToasts.supported(), "Unsupported toast control");
        CbbgConfig.setMode(CbbgConfig.Mode.DISABLED);
        render(-1, -1, 0);
        require(button(0).active && button(8).active && !button(1).active && !button(5).active,
                "Disabled mode controls were not locked");
        CbbgConfig before = CbbgConfig.get();
        click(1);
        seed("123");
        require(before.equals(CbbgConfig.get()), "Locked controls changed config");
        click(0);
        require(CbbgConfig.get().mode() != CbbgConfig.Mode.DISABLED && button(1).active,
                "Mode did not unlock controls immediately");
        CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA16F);
        click(1);
        require(CbbgConfig.get().pixelFormat() == CbbgConfig.PixelFormat.RGBA16F, "Format no longer stays on RGBA16F");
        click(1);
        require(CbbgConfig.get().pixelFormat() == CbbgConfig.PixelFormat.RGBA16F, "Repeated format selection did not stay on RGBA16F");
        slider(2, false);
        require(CbbgConfig.get().strength() == 0.5f, "Minimum strength mismatch");
        slider(2, true);
        require(CbbgConfig.get().strength() == 4f, "Maximum strength mismatch");
        slider(3, false);
        require(CbbgConfig.get().stbnSize() == 16, "Minimum size mismatch");
        slider(3, true);
        require(CbbgConfig.get().stbnSize() == 256, "Maximum size mismatch");
        slider(4, false);
        require(CbbgConfig.get().stbnDepth() == 8, "Minimum depth mismatch");
        slider(4, true);
        require(CbbgConfig.get().stbnDepth() == 128, "Maximum depth mismatch");
        seed(Long.toString(Long.MIN_VALUE));
        require(CbbgConfig.get().stbnSeed() == Long.MIN_VALUE, "Signed seed input mismatch");
        keyPressed('0', 0);
        require(CbbgConfig.get().stbnSeed() == Long.MIN_VALUE && !button(5).active,
                "Overflowing seed mutated config or enabled generation");
        seed("-");
        require(!button(5).active, "Incomplete seed enabled generation");
        seed("");
        require(CbbgConfig.get().stbnSeed() == 0 && button(5).active, "Empty seed should mean zero");
        boolean chat = CbbgConfig.get().notifyChat();
        click(6);
        require(CbbgConfig.get().notifyChat() != chat, "Chat preference did not change");
        if (LegacyToasts.supported()) {
            boolean toast = CbbgConfig.get().notifyToast();
            click(7);
            require(CbbgConfig.get().notifyToast() != toast, "Toast preference did not change");
        }
        int started = LegacyNotificationParity.started;
        click(5);
        require(client.currentScreen instanceof ConfirmScreen && LegacyNotificationParity.started == started,
                "Generate did not wait for native confirmation");
        confirmResult(false, 0);
        require(client.currentScreen == this && LegacyNotificationParity.started == started,
                "Cancelling generation started work or lost the settings screen");
        render(-1, -1, 0);
        click(8);
        require(client.currentScreen == parent, "Done did not return to parent");
        keyPressed('\0', 1);
        require(client.currentScreen == parent, "Escape did not return to parent");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
