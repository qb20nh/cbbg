package com.qb20nh.cbbg.gametest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;

/** Compares packaged world screenshots to the CPU oracle on RenderScale's actual pixel grid. */
@NullMarked
public final class ReleaseRenderScaleGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    if (!FabricLoader.getInstance().isModLoaded("renderscale")) {
      throw new AssertionError("RenderScale fixture requires the RenderScale mod");
    }
    Object renderer =
        context.computeOnClient(
            client -> Objects.requireNonNull(RenderScaleTestAccess.call(null, "getInstance")));
    Object config =
        context.computeOnClient(
            client -> Objects.requireNonNull(RenderScaleTestAccess.call(null, "getConfig")));
    Map<String, Object> original = context.computeOnClient(client -> settings(config));
    try {
      for (float value : new float[] {0.5f, 1, 2, 0.7f, 0.3333f}) {
        context.runOnClient(
            client -> {
              for (String name : original.keySet()) {
                Object setting =
                    switch (name) {
                      case "scale" -> value;
                      case "targetFrameRate" -> 0;
                      default -> false;
                    };
                RenderScaleTestAccess.set(config, name, setting);
              }
              RenderScaleTestAccess.call(renderer, "onResolutionChanged");
            });
        ReleaseWorldPixelsGameTest.runScaledScene(context, value);
      }
    } finally {
      context.runOnClient(
          client -> {
            original.forEach((name, setting) -> RenderScaleTestAccess.set(config, name, setting));
            RenderScaleTestAccess.call(renderer, "onResolutionChanged");
          });
    }
  }

  private static Map<String, Object> settings(Object config) {
    Map<String, Object> settings = new LinkedHashMap<>();
    // RenderScale versions expose different FSR and dynamic-scale options. Save
    // the options supported by the loaded mod and use a static nearest blit.
    Set<String> options = Set.of("scale", "forceLinear", "fsr", "targetFrameRate");
    for (var field : config.getClass().getFields()) {
      if (options.contains(field.getName())) {
        try {
          settings.put(field.getName(), Objects.requireNonNull(field.get(config)));
        } catch (IllegalAccessException failure) {
          throw new LinkageError("Could not read RenderScale option " + field.getName(), failure);
        }
      }
    }
    if (!settings.containsKey("scale") || !settings.containsKey("forceLinear")) {
      throw new AssertionError("RenderScale configuration API changed");
    }
    return settings;
  }
}
