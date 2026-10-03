package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class ReleaseScreenshots {
  private ReleaseScreenshots() {}

  static Path capture(ClientGameTestContext context, String name, Consumer<NativeImage> check) {
    Path path =
        Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")), name + ".png");
    CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
    context.runOnClient(
        client ->
            Screenshot.takeScreenshot(
                client.getMainRenderTarget(),
                1,
                image -> {
                  try (image) {
                    Files.createDirectories(Objects.requireNonNull(path.getParent()));
                    image.writeToFile(path);
                    check.accept(image);
                    result.complete(null);
                  } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                  }
                }));
    context.waitFor(client -> result.isDone(), 200);
    result.join();
    return path;
  }
}
