package com.qb20nh.cbbg.compat.modmenu;

import com.qb20nh.cbbg.config.gui.CbbgConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class CbbgModMenuApi implements ModMenuApi {
  @Override
  public ConfigScreenFactory<?> getModConfigScreenFactory() {
    return CbbgConfigScreen::new;
  }
}
