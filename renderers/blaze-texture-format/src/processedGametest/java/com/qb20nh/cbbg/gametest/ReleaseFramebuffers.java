package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;

@NullMarked
final class ReleaseFramebuffers {
  private ReleaseFramebuffers() {}

  static void customTargets() {
    for (@Nullable String label : new String[] {null, "Other renderer"}) {
      TextureTarget target = new TextureTarget(label, 32, 24, true);
      try {
        GpuTexture old = Objects.requireNonNull(target.getColorTexture());
        if (ReleaseLifecycleGameTest.format(old) != GL11.GL_RGBA8) {
          throw new AssertionError("CBBG changed an unrelated framebuffer: " + label);
        }
        target.resize(48, 32);
        if (!old.isClosed()
            || target.width != 48
            || target.height != 32
            || ReleaseLifecycleGameTest.format(Objects.requireNonNull(target.getColorTexture()))
                != GL11.GL_RGBA8) {
          throw new AssertionError("Custom framebuffer resize failed: " + label);
        }
      } finally {
        target.destroyBuffers();
      }
    }
  }

  static void blur(Minecraft client, int expected) {
    long before = ReleaseObservations.blurAllocations();
    client.gameRenderer.processBlurEffect();
    if (ReleaseObservations.blurAllocations() <= before
        || ReleaseObservations.blurFormat() != expected) {
      throw new AssertionError(
          "Menu blur did not allocate the expected format: "
              + expected
              + ", observed="
              + ReleaseObservations.blurFormat());
    }
  }
}
