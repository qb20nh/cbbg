package com.qb20nh.cbbg.debug;

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class DebugScreenLine {
  private DebugScreenLine() {}

  static void add(DebugScreenDisplayer displayer, String line) {
    displayer.addLine(line);
  }
}
