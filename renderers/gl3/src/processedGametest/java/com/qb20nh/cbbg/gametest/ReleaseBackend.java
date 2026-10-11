package com.qb20nh.cbbg.gametest;

import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;

@NullMarked
final class ReleaseBackend {
  private ReleaseBackend() {}

  static String[] identity() {
    return new String[] {
      "opengl",
      String.valueOf(GL11.glGetString(GL11.GL_RENDERER)),
      String.valueOf(GL11.glGetString(GL11.GL_VERSION))
    };
  }
}
