package com.qb20nh.cbbg.config.gui;

import com.qb20nh.cbbg.LegacyToasts;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.LegacyDither;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.IdentifiableBooleanConsumer;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.TranslatableText;
import org.lwjgl.input.Keyboard;

/** Shared legacy controls; only text-field construction and clicks differ by version. */
public class CbbgConfigScreen extends Screen implements IdentifiableBooleanConsumer {
    private final Screen parent;
    private TextFieldWidget seed;
    private int top;

    public CbbgConfigScreen(Screen parent) {
        this.parent = parent;
    }

    private static String text(String key, Object... args) {
        return new TranslatableText(key, args).asFormattedString();
    }

    private static String label(String key, Object value) {
        return text("cbbg.config.labeled_value", text("cbbg.config." + key), value);
    }

    @Override
    public void init() {
        Keyboard.enableRepeatEvents(true);
        buttons.clear();
        top = Math.max(8, (height - 238) / 2);
        int x = width / 2 - 100;
        int y = top + 20;
        CbbgConfig config = CbbgConfig.get();
        buttons.add(new ButtonWidget(0, x, y, 200, 20, ""));
        buttons.add(new ButtonWidget(1, x, y + 24, 200, 20, ""));
        buttons.add(new Slider(2, x, y + 48, 200, "strength.label", 0.5, 4, config.strength(), false,
                value -> CbbgConfig.setStrength((float) value)));
        buttons.add(new Slider(3, x, y + 72, 98, "size.label", 4, 8,
                Integer.numberOfTrailingZeros(config.stbnSize()), true, value -> CbbgConfig.setStbnSize((int) value)));
        buttons.add(new Slider(4, x + 102, y + 72, 98, "depth.label", 3, 7,
                Integer.numberOfTrailingZeros(config.stbnDepth()), true, value -> CbbgConfig.setStbnDepth((int) value)));
        String previous = seed == null ? Long.toString(config.stbnSeed()) : seed.getText();
        seed = LegacyWidgets.seed(textRenderer, x + 40, y + 96);
        seed.setMaxLength(32);
        seed.setText(previous);
        buttons.add(new ButtonWidget(5, x, y + 120, 200, 20, text("cbbg.config.button.generate_stbn")));
        buttons.add(new ButtonWidget(6, x, y + 144, LegacyToasts.supported() ? 98 : 200, 20, ""));
        if (LegacyToasts.supported()) buttons.add(new ButtonWidget(7, x + 102, y + 144, 98, 20, ""));
        buttons.add(new ButtonWidget(8, x, y + 168, 200, 20, text("cbbg.config.button.done")));
        refresh();
    }

    private boolean editable() {
        return !LegacyDither.hasFailed() && CbbgConfig.get().mode() != CbbgConfig.Mode.DISABLED;
    }

    private Long parsedSeed() {
        String value = seed.getText();
        if (value.isEmpty()) return 0L;
        if (!value.matches("-?[0-9]+")) return null;
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { return null; }
    }

    private void refresh() {
        CbbgConfig config = CbbgConfig.get();
        for (Object entry : buttons) {
            ButtonWidget button = (ButtonWidget) entry;
            button.active = button.id == 8 || (button.id == 0 ? !LegacyDither.hasFailed() : editable());
            if (button.id == 0) button.message = label("mode", text("cbbg.mode." + config.mode().getSerializedName()));
            if (button.id == 1) button.message = label("format", text("cbbg.pixel_format." + config.pixelFormat().getSerializedName()));
            if (button.id == 5) button.active &= parsedSeed() != null;
            if (button.id == 6) button.message = label("notify.chat", text(config.notifyChat() ? "options.on" : "options.off"));
            if (button.id == 7) button.message = label("notify.toast", text(config.notifyToast() ? "options.on" : "options.off"));
        }
        seed.setEditable(editable());
        seed.setEditableColor(parsedSeed() == null ? 0xFF5555 : 0xE0E0E0);
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        refresh();
        if (!button.active) return;
        CbbgConfig config = CbbgConfig.get();
        switch (button.id) {
            case 0:
                CbbgConfig.Mode[] modes = CbbgConfig.Mode.values();
                CbbgConfig.setMode(modes[(config.mode().ordinal() + 1) % modes.length]);
                break;
            case 1:
                CbbgConfig.setPixelFormat(config.pixelFormat() == CbbgConfig.PixelFormat.RGBA16F
                        ? CbbgConfig.PixelFormat.RGBA32F : CbbgConfig.PixelFormat.RGBA16F);
                break;
            case 5:
                CbbgConfig.setStbnSeed(parsedSeed());
                client.setScreen(new ConfirmScreen(this,
                        text("cbbg.config.confirm.regenerate_stbn.title"),
                        text("cbbg.config.confirm.regenerate_stbn.message", config.stbnSize(), config.stbnDepth(), parsedSeed()), 0));
                break;
            case 6: CbbgConfig.setNotifyChat(!config.notifyChat()); break;
            case 7: CbbgConfig.setNotifyToast(!config.notifyToast()); break;
            case 8: client.setScreen(parent); break;
            default: break;
        }
        refresh();
    }

    @Override
    public void confirmResult(boolean confirmed, int id) {
        if (confirmed && editable()) LegacyDither.regenerate();
        client.setScreen(this);
    }

    @Override
    protected void keyPressed(char character, int code) {
        if (code == 1) { client.setScreen(parent); return; }
        if (code == 15 && editable()) seed.setFocused(!seed.isFocused());
        if (editable() && seed.keyPressed(character, code)) {
            Long value = parsedSeed();
            if (value != null) CbbgConfig.setStbnSeed(value);
        }
        refresh();
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        refresh();
        LegacyWidgets.click(seed, x, y, button);
        super.mouseClicked(x, y, button);
    }

    @Override public void tick() { seed.tick(); }
    @Override public void removed() { Keyboard.enableRepeatEvents(false); }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        renderBackground();
        refresh();
        drawCenteredString(textRenderer, text("cbbg.config.title"), width / 2, top, 0xFFFFFF);
        textRenderer.draw(text("cbbg.config.seed.label"), width / 2 - 100, top + 122, 0xAAAAAA);
        super.render(mouseX, mouseY, delta);
        seed.render();
        if (LegacyDither.hasFailed() || !editable()) {
            drawCenteredString(textRenderer, text(LegacyDither.hasFailed()
                    ? "cbbg.config.status.disabled_render_error" : "cbbg.config.status.mode_disabled"),
                    width / 2, top + 216, 0xAAAAAA);
        }
        for (Object entry : buttons) {
            ButtonWidget button = (ButtonWidget) entry;
            if (!button.isHovered()) continue;
            String key = button.id == 0 ? "mode.tooltip." + CbbgConfig.get().mode().getSerializedName()
                    : button.id == 2 ? "tooltip.strength" : button.id == 3 ? "tooltip.stbn_size"
                    : button.id == 4 ? "tooltip.stbn_depth" : button.id == 5 ? "tooltip.generate_stbn" : null;
            if (key != null) renderTooltip(Arrays.asList(text("cbbg.config." + key).split("\n", -1)), mouseX, mouseY);
        }
        if (mouseX >= width / 2 - 60 && mouseX < width / 2 + 100 && mouseY >= top + 116 && mouseY < top + 136) {
            renderTooltip(Arrays.asList(text("cbbg.config.tooltip.stbn_seed").split("\n", -1)), mouseX, mouseY);
        }
    }

    private static final class Slider extends ButtonWidget {
        private final String key;
        private final double min;
        private final double max;
        private final boolean power;
        private final DoubleConsumer setter;
        private double position;
        private boolean dragging;

        Slider(int id, int x, int y, int width, String key, double min, double max, double current,
                boolean power, DoubleConsumer setter) {
            super(id, x, y, width, 20, "");
            this.key = key;
            this.min = min;
            this.max = max;
            this.power = power;
            this.setter = setter;
            position = (current - min) / (max - min);
            update(false);
        }

        private void update(boolean apply) {
            double value = min + position * (max - min);
            if (power) value = 1 << (int) Math.round(value);
            message = label(key, power ? Integer.toString((int) value) : String.format(Locale.ROOT, "%.2f", value));
            if (apply) setter.accept(value);
        }

        private void drag(int mouseX) {
            position = Math.max(0, Math.min(1, (mouseX - x - 4) / (double) (width - 8)));
            update(true);
        }

        @Override public int getYImage(boolean hovered) { return 0; }
        @Override public boolean isMouseOver(MinecraftClient client, int mouseX, int mouseY) {
            if (!super.isMouseOver(client, mouseX, mouseY)) return false;
            dragging = true;
            drag(mouseX);
            return true;
        }
        @Override protected void mouseDragged(MinecraftClient client, int mouseX, int mouseY) {
            if (dragging && active) drag(mouseX);
            int knob = x + (int) (position * (width - 8));
            fill(knob, y + 1, knob + 8, y + 19, active ? 0xFFAAAAAA : 0xFF555555);
        }
        @Override public void mouseReleased(int mouseX, int mouseY) { dragging = false; }
    }
}
