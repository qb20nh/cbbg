package com.qb20nh.cbbg.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL30;

/** Reloads an invalid dither fragment, then restores the packaged shader in the same world. */
final class ClassicOpenGlShaderSmoke {
  private static final String BROKEN = "file/cbbg-test-broken-shader";

  private enum Stage {
    BEFORE,
    BROKEN,
    RESTORED
  }

  private final List<String> originalPacks;
  private Stage stage = Stage.BEFORE;
  private CompletableFuture<Void> reload = CompletableFuture.completedFuture(null);
  private int readyAt = -1;
  private long baseline;

  ClassicOpenGlShaderSmoke(Minecraft client) {
    var packs = client.getResourcePackRepository();
    packs.reload();
    originalPacks = List.copyOf(packs.getSelectedIds());
    if (!packs.getAvailableIds().contains(BROKEN) || originalPacks.contains(BROKEN)) {
      throw new AssertionError("Missing or already active broken OpenGL shader resource pack");
    }
  }

  boolean tick(Minecraft client, int ticks) throws Exception {
    if (!reload.isDone()
        || client.getOverlay() != null
        || client.player == null
        || client.level == null
        || client.screen != null) return false;
    reload.join();
    var packs = client.getResourcePackRepository();
    if (packs.getSelectedIds().contains(BROKEN) != (stage == Stage.BROKEN)) {
      throw new AssertionError("The broken shader resource pack was discarded before fallback");
    }
    long count = ClassicPackagedSmoke.presentationCount();
    if (readyAt < 0) {
      readyAt = ticks + 8;
      baseline = count;
      return false;
    }
    if (ticks < readyAt) return false;
    long draws = count - baseline;
    if (stage == Stage.BROKEN ? draws != 0 : draws < 6) {
      throw new AssertionError("Unexpected dither draws during " + stage + ": " + draws);
    }
    ClassicPackagedSmoke.requireFormat(client, GL30.GL_RGBA16F);
    String name = stage.name().toLowerCase(java.util.Locale.ROOT);
    ClassicPackagedSmoke.capture(client, "opengl-shader-" + name + ".png");
    System.out.println("CBBG_CLASSIC_OPENGL_SHADER stage=" + name + " draws=" + draws);
    if (stage == Stage.RESTORED) return true;
    if (stage == Stage.BEFORE) {
      var selected = new ArrayList<>(originalPacks);
      selected.add(BROKEN);
      packs.setSelected(selected);
      stage = Stage.BROKEN;
    } else {
      packs.setSelected(originalPacks);
      stage = Stage.RESTORED;
    }
    reload = client.reloadResourcePacks();
    readyAt = -1;
    return false;
  }
}
