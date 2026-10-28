package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

@NullMarked
final class ReleaseShutdownResources {
  private ReleaseShutdownResources() {}

  static @Nullable Live live() {
    int noise =
        (Integer)
            Objects.requireNonNull(
                ReleaseShutdownGameTest.field(
                    "com.qb20nh.cbbg.render.DitherPresentation", null, "int texture"));
    Object pass =
        Objects.requireNonNull(
            ReleaseShutdownGameTest.field(
                "com.qb20nh.cbbg.render.CbbgDither",
                null,
                "com.qb20nh.cbbg.render.DitherPass PASS"));
    TextureTarget output =
        (TextureTarget)
            ReleaseShutdownGameTest.field(
                "com.qb20nh.cbbg.render.DitherPass",
                pass,
                "com.mojang.blaze3d.pipeline.TextureTarget output");
    if (noise <= 0
        || !GL11.glIsTexture(noise)
        || (output != null
            && (!GL11.glIsTexture(output.getColorTextureId())
                || !GL30.glIsFramebuffer(output.frameBufferId)))) return null;
    var textures = new HashSet<Integer>();
    textures.add(noise);
    for (Object value : ReleaseNoiseTextures.textures().values()) textures.add((Integer) value);
    return new Live(
        List.copyOf(textures),
        output == null ? -1 : output.getColorTextureId(),
        output == null ? -1 : output.frameBufferId,
        output);
  }

  static int liveCount(Live live) {
    return live.noise().size() + (live.output() == null ? 0 : 2);
  }

  static boolean liveClosed(Live live) {
    return live.noise().stream().noneMatch(GL11::glIsTexture)
        && (live.output() == null
            || (!GL11.glIsTexture(live.color())
                && !GL30.glIsFramebuffer(live.framebuffer())
                && live.output().getColorTextureId() <= 0
                && live.output().frameBufferId < 0));
  }

  static Owned captureOwned() {
    NativeImage[] frames =
        (NativeImage[])
            Objects.requireNonNull(
                ReleaseShutdownGameTest.field(
                    "com.qb20nh.cbbg.render.DitherController",
                    null,
                    "com.mojang.blaze3d.platform.NativeImage[] frames"));
    return new Owned(List.copyOf(Arrays.asList(frames.clone())));
  }

  static int ownedCount(Owned owned) {
    return owned.frames().size();
  }

  static boolean ownedClosed(Owned owned) {
    for (NativeImage frame : owned.frames()) {
      try {
        var pixels =
            NativeImage.class.getDeclaredField(ReleaseGameNames.field(NativeImage.class, "pixels"));
        pixels.setAccessible(true);
        if (pixels.getLong(frame) != 0) return false;
      } catch (ReflectiveOperationException failure) {
        throw new LinkageError("Cannot inspect packaged noise image disposal", failure);
      }
    }
    return true;
  }

  record Live(List<Integer> noise, int color, int framebuffer, @Nullable TextureTarget output) {}

  record Owned(List<NativeImage> frames) {}
}
