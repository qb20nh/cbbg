package com.qb20nh.cbbg.compat.sodium;

import com.qb20nh.cbbg.config.gui.CbbgConfigScreen;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;

/** Opens CBBG settings from Sodium's Video Settings. */
@NullMarked
public final class CbbgSodiumConfig implements ConfigEntryPoint {
  @Override
  public void registerConfigLate(ConfigBuilder builder) {
    builder
        .registerOwnModOptions()
        .addPage(
            builder
                .createExternalPage()
                .setName(Component.translatable("cbbg.config.title"))
                .setScreenConsumer(CbbgConfigScreen::open));
  }
}
