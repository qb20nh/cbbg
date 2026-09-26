package com.qb20nh.cbbg;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class CbbgEarlyInit implements PreLaunchEntrypoint {

  @Override
  public void onPreLaunch() {
    startPreparation();
  }

  static void startPreparation() {
    configureSettings();
    CbbgConfig cfg = CbbgConfig.get();
    STBNGenerator.generateAsync(cfg.stbnSize(), cfg.stbnSize(), cfg.stbnDepth(), cfg.stbnSeed())
        .exceptionally(
            failure -> {
              Throwable cause =
                  failure instanceof CompletionException && failure.getCause() != null
                      ? failure.getCause()
                      : failure;
              if (!(cause instanceof CancellationException)) {
                Cbbg.LOGGER.error("Failed to prepare startup STBN generation", cause);
              }
              return null;
            });
  }

  static void configureSettings() {
    CbbgConfig.configure(
        FabricLoader.getInstance().getConfigDir().resolve(Cbbg.MOD_ID + ".json"),
        Cbbg.LOGGER::warn);
  }
}
