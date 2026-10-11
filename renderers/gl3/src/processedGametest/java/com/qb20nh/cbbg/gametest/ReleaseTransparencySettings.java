package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.qb20nh.cbbg.gametest.mixin.ReleasePostTargetsAccess;
import com.qb20nh.cbbg.gametest.mixin.ReleaseTransparencyAccess;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;

/** Inspect the actual vanilla Fabulous chain, retaining Sodium's replacement as a distinct path. */
@NullMarked
final class ReleaseTransparencySettings {
  private ReleaseTransparencySettings() {}

  static Object snapshot(Minecraft client) {
    return client.options.graphicsMode().get();
  }

  static boolean enabled(Minecraft client) {
    return client.options.graphicsMode().get() == GraphicsStatus.FABULOUS;
  }

  static void set(Minecraft client, boolean enabled) {
    change(client, enabled ? GraphicsStatus.FABULOUS : GraphicsStatus.FANCY);
  }

  static void restore(Minecraft client, Object original) {
    change(client, (GraphicsStatus) original);
  }

  private static void change(Minecraft client, GraphicsStatus status) {
    if (client.options.graphicsMode().get() != status) {
      client.options.graphicsMode().set(status);
      client.levelRenderer.allChanged();
    }
  }

  static JsonObject evidence(Minecraft client, boolean floating) {
    boolean fabulous = enabled(client);
    boolean sodium = FabricLoader.getInstance().isModLoaded("sodium");
    var chain = ((ReleaseTransparencyAccess) client.levelRenderer).cbbg$transparencyChain();
    long passes = chain == null ? 0 : ReleaseTransparencyObservations.passes(chain);
    if (!sodium && (chain != null) != fabulous) {
      throw new AssertionError(
          "Vanilla Fabulous selection did not match its live transparency chain");
    }
    if (!sodium && fabulous && passes == 0) {
      throw new AssertionError("Vanilla Fabulous chain was allocated but never rendered the world");
    }
    JsonObject evidence = new JsonObject();
    evidence.addProperty("graphicsStatus", client.options.graphicsMode().get().name());
    evidence.addProperty("fabulousRequested", fabulous);
    evidence.addProperty("vanillaFabulousChainAllocated", chain != null);
    evidence.addProperty("vanillaFabulousPasses", passes);
    evidence.addProperty(
        "path", passes > 0 ? "vanilla-fabulous" : sodium ? "sodium" : "vanilla-forward");
    JsonObject formats = new JsonObject();
    if (chain != null) {
      var targets = ((ReleasePostTargetsAccess) chain).cbbg$targets();
      if (!targets.containsKey("translucent") || !targets.containsKey("final")) {
        throw new AssertionError("Fabulous transparency targets changed");
      }
      String expected = floating ? "rgba32f" : "rgba8";
      targets.forEach(
          (name, target) -> {
            String format = ReleaseWorldCapture.format(target);
            formats.addProperty(name, format);
            if (!format.equals(expected)) {
              throw new AssertionError(
                  "Fabulous target " + name + " retained " + format + ", expected " + expected);
            }
          });
    }
    evidence.add("targets", formats);
    return evidence;
  }
}
