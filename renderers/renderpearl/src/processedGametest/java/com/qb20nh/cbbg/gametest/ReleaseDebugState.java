package com.qb20nh.cbbg.gametest;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;

/** Reads the packaged mod's public debug entry on the render thread. */
@NullMarked
final class ReleaseDebugState {
  private ReleaseDebugState() {}

  static List<String> read(Minecraft client) {
    List<String> lines = new ArrayList<>();
    var displayer =
        (DebugScreenDisplayer)
            Proxy.newProxyInstance(
                DebugScreenDisplayer.class.getClassLoader(),
                new Class<?>[] {DebugScreenDisplayer.class},
                (proxy, method, args) -> {
                  if (!method.getName().equals("addLine")) throw new AssertionError(method);
                  lines.add((String) Objects.requireNonNull(args)[0]);
                  return null;
                });
    var entry = DebugScreenEntries.getEntry(Identifier.fromNamespaceAndPath("cbbg", "cbbg"));
    if (entry == null) throw new AssertionError("CBBG debug entry is missing");
    entry.display(displayer, client.level, null, null);
    return List.copyOf(lines);
  }
}
