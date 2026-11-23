package com.qb20nh.cbbg.compat.modmenu;

import com.qb20nh.cbbg.config.gui.CbbgConfigScreen;
import io.github.prospector.modmenu.api.ConfigScreenFactory;
import io.github.prospector.modmenu.api.ModMenuApi;

public final class CbbgModMenuApi implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return CbbgConfigScreen::new;
    }
}
