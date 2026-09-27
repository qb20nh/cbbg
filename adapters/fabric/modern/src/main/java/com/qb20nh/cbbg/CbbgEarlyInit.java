package com.qb20nh.cbbg;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import java.util.concurrent.CancellationException;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.jspecify.annotations.NullMarked;
import org.slf4j.LoggerFactory;

@NullMarked
public final class CbbgEarlyInit implements PreLaunchEntrypoint {
  private static boolean started;

  @Override
  public void onPreLaunch() {
    startPreparation();
  }

  static synchronized void startPreparation() {
    if (started) return;
    configureSettings();
    CbbgConfig cfg = CbbgConfig.get();
    STBNGenerator.prepareEarly(cfg.stbnSize(), cfg.stbnSize(), cfg.stbnDepth(), cfg.stbnSeed())
        .exceptionally(
            failure -> {
              if (!(failure instanceof CancellationException)
                  && !(failure.getCause() instanceof CancellationException)) {
                LoggerFactory.getLogger("cbbg").error("Early STBN preparation failed", failure);
              }
              return null;
            });
    started = true;
  }

  private static void configureSettings() {
    CbbgConfig.configure(
        FabricLoader.getInstance().getConfigDir().resolve("cbbg.json"),
        LoggerFactory.getLogger("cbbg")::warn);
  }
}
