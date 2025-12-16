package com.qb20nh.cbbg.compat.modmenu;

import com.qb20nh.cbbg.config.gui.CbbgConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.minecraft.client.gui.screens.Screen;

public final class CbbgModMenuApi implements ModMenuApi {
  @Override
  public ConfigScreenFactory<Screen> getModConfigScreenFactory() {
    return CbbgConfigScreen::new;
  }
}
