package com.qb20nh.cbbg.config.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.TextFieldWidget;

final class LegacyWidgets {
    static TextFieldWidget seed(TextRenderer font, int x, int y) {
        return new TextFieldWidget(font, x, y, 160, 20);
    }

    static void click(TextFieldWidget field, int x, int y, int button) {
        field.mouseClicked(x, y, button);
    }
}
