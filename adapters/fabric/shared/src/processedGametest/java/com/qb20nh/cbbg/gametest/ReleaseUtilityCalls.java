package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.qb20nh.cbbg.api.DitherOptions;
import com.qb20nh.cbbg.render.DitherPass;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseUtilityCalls {
  private ReleaseUtilityCalls() {}

  static TextureTarget render(
      DitherPass pass, TextureTarget source, UtilitiesBackend.Noise noise, DitherOptions options) {
    return pass.render(Objects.requireNonNull(source.getColorTextureView()), noise.view(), options);
  }

  static void resize(TextureTarget source, int width, int height) {
    source.resize(width, height);
  }

  static void checkClosed(
      DitherPass pass, TextureTarget output, TextureTarget source, UtilitiesBackend.Noise noise) {
    var owned = Objects.requireNonNull(output.getColorTexture());
    pass.close();
    if (!owned.isClosed()) throw new AssertionError("Utility pass retained its output");
    if (noise.texture().isClosed()
        || noise.view().isClosed()
        || Objects.requireNonNull(source.getColorTexture()).isClosed()) {
      throw new AssertionError("Utility pass closed externally owned input textures");
    }
  }

  static void capture(TextureTarget output, Consumer<NativeImage> consumer) {
    Screenshot.takeScreenshot(output, consumer);
  }
}
