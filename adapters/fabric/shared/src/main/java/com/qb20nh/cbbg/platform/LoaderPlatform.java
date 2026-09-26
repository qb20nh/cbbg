package com.qb20nh.cbbg.platform;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Loader services used by shared cache and optional-mod code. */
public final class LoaderPlatform {
  private LoaderPlatform() {}

  public static Path gameDirectory() {
    return FabricLoader.getInstance().getGameDir();
  }

  public static boolean isModLoaded(String id) {
    return FabricLoader.getInstance().isModLoaded(id);
  }
}
