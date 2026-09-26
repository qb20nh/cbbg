package com.qb20nh.cbbg;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.NullMarked;

/** Starts CPU preparation while Fabric constructs language adapters. */
@NullMarked
public final class CbbgLanguageAdapter implements LanguageAdapter {
  public CbbgLanguageAdapter() {
    CbbgEarlyInit.startPreparation();
  }

  @Override
  public <T> T create(ModContainer mod, String value, Class<T> type)
      throws LanguageAdapterException {
    return LanguageAdapter.getDefault().create(mod, value, type);
  }
}
