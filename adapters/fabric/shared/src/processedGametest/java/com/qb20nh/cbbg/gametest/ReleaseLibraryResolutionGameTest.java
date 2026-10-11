package com.qb20nh.cbbg.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ReleaseLibraryResolutionGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    var loader = FabricLoader.getInstance();
    long count =
        loader.getAllMods().stream()
            .filter(mod -> mod.getMetadata().getId().equals("cbbg_lib"))
            .count();
    if (!loader.isModLoaded("cbbg") || count != 1) {
      throw new AssertionError("CBBG must resolve exactly one CBBG Lib instance");
    }
  }
}
