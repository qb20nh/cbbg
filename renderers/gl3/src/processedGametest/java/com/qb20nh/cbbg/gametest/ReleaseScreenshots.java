package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ReleaseScreenshots {
  private ReleaseScreenshots() {}

  static Path capture(ClientGameTestContext context, String name, Consumer<NativeImage> check) {
    Path path =
        Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")), name + ".png");
    context.runOnClient(
        client -> {
          try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
            Files.createDirectories(Objects.requireNonNull(path.getParent()));
            image.writeToFile(path);
            check.accept(image);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
    return path;
  }
}
