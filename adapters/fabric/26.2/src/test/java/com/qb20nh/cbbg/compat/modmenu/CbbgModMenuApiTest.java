package com.qb20nh.cbbg.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@NullMarked
class CbbgModMenuApiTest {

  @Test
  void configScreenFactory_isProvided() {
    CbbgModMenuApi api = new CbbgModMenuApi();
    ConfigScreenFactory<Screen> factory = api.getModConfigScreenFactory();
    Assertions.assertNotNull(factory);
  }
}
