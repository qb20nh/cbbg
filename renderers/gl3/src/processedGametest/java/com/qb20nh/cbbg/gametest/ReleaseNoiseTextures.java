package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL11;

/** Checks cached noise pixels, reuse, disposal and allocation fallback. */
@NullMarked
public final class ReleaseNoiseTextures {
  private static int skipAfter;

  private ReleaseNoiseTextures() {}

  public static boolean skipAllocation(int width, int height) {
    if (width != 16 || height != 16 || skipAfter <= 0) return false;
    return --skipAfter == 0;
  }

  static void check(ClientGameTestContext context) {
    context.waitTicks(12);
    Set<Integer> cached = context.computeOnClient(client -> checkPixels());
    context.waitTicks(12);
    context.runOnClient(
        client -> {
          if (!cached.equals(new HashSet<>(textures().values())))
            throw new AssertionError("Noise textures were replaced during reuse");
        });
    ReleaseLifecycleGameTest.command(context, "mode set disabled");
    ReleaseLifecycleGameTest.noPresentations(context);
    context.runOnClient(client -> assertClosed(cached));
    try {
      context.runOnClient(client -> skipAfter = 2);
      ReleaseLifecycleGameTest.command(context, "mode set enabled");
      ReleaseLifecycleGameTest.awaitPresentation(context);
      context.waitTicks(8);
      int fallback =
          context.computeOnClient(
              client -> {
                if (skipAfter != 0
                    || !textures().isEmpty()
                    || !Boolean.TRUE.equals(field("boolean streaming")))
                  throw new AssertionError("Noise allocation failure did not select streaming");
                return ReleaseObservations.noise();
              });
      ReleasePixels.check(context, false);
      context.waitTicks(8);
      context.runOnClient(
          client -> {
            if (ReleaseObservations.noise() != fallback || !GL11.glIsTexture(fallback))
              throw new AssertionError("Streaming did not reuse its remaining texture");
          });
      ReleaseLifecycleGameTest.command(context, "mode set disabled");
      ReleaseLifecycleGameTest.noPresentations(context);
      context.runOnClient(client -> assertClosed(Set.of(fallback)));
    } finally {
      context.runOnClient(client -> skipAfter = 0);
    }
    ReleaseLifecycleGameTest.command(context, "mode set enabled");
    ReleaseLifecycleGameTest.awaitPresentation(context);
  }

  private static Object field(String declaration) {
    return Objects.requireNonNull(
        ReleaseShutdownGameTest.field(
            "com.qb20nh.cbbg.render.DitherPresentation", null, declaration));
  }

  static IdentityHashMap<?, ?> textures() {
    return (IdentityHashMap<?, ?>) field("java.util.IdentityHashMap textures");
  }

  private static Set<Integer> checkPixels() {
    NativeImage[] frames =
        (NativeImage[])
            Objects.requireNonNull(
                ReleaseShutdownGameTest.field(
                    "com.qb20nh.cbbg.render.DitherController",
                    null,
                    "com.mojang.blaze3d.platform.NativeImage[] frames"));
    IdentityHashMap<?, ?> textures = textures();
    if (textures.size() != frames.length || frames.length != 8)
      throw new AssertionError("Not every noise frame has a cached texture");
    var ids = new HashSet<Integer>();
    int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    int[] packing = {
      GL11.GL_PACK_ROW_LENGTH,
      GL11.GL_PACK_SKIP_PIXELS,
      GL11.GL_PACK_SKIP_ROWS,
      GL11.GL_PACK_ALIGNMENT
    };
    int[] saved = new int[packing.length];
    for (int i = 0; i < packing.length; i++) {
      saved[i] = GL11.glGetInteger(packing[i]);
      GL11.glPixelStorei(packing[i], i == 3 ? 4 : 0);
    }
    try {
      for (NativeImage frame : frames) {
        int texture = (Integer) Objects.requireNonNull(textures.get(frame));
        if (!ids.add(texture) || !GL11.glIsTexture(texture))
          throw new AssertionError("Noise frames do not own distinct live textures");
        GlStateManager._bindTexture(texture);
        try (var actual = new NativeImage(frame.getWidth(), frame.getHeight(), false)) {
          actual.downloadTexture(0, false);
          for (int y = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++) {
              if (actual.getPixelRGBA(x, y) != frame.getPixelRGBA(x, y))
                throw new AssertionError("Cached noise pixels differ from their source");
            }
          }
        }
      }
      return ids;
    } finally {
      for (int i = 0; i < packing.length; i++) GL11.glPixelStorei(packing[i], saved[i]);
      GlStateManager._bindTexture(previous);
    }
  }

  private static void assertClosed(Set<Integer> textures) {
    for (int texture : textures) {
      if (GL11.glIsTexture(texture))
        throw new AssertionError("Noise texture was retained after disable");
    }
  }
}
