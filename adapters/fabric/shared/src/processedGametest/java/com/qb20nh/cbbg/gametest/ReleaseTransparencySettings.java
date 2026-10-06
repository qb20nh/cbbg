package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;

/** Modern improved-transparency option; the legacy adapter checks the Fabulous chain instead. */
@NullMarked
final class ReleaseTransparencySettings {
  private ReleaseTransparencySettings() {}

  static Object snapshot(Minecraft client) {
    return client.options.improvedTransparency().get();
  }

  static boolean enabled(Minecraft client) {
    return client.options.improvedTransparency().get();
  }

  static void set(Minecraft client, boolean enabled) {
    client.options.improvedTransparency().set(enabled);
  }

  static void restore(Minecraft client, Object original) {
    set(client, (Boolean) original);
  }

  static JsonObject evidence(Minecraft client, boolean floating) {
    JsonObject evidence = new JsonObject();
    evidence.addProperty("path", "improved-transparency");
    evidence.addProperty("enabled", enabled(client));
    return evidence;
  }
}
