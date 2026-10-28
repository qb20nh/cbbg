package com.qb20nh.cbbg.gametest;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import org.jspecify.annotations.NullMarked;

/** Checks that resource-pack replacements use the original presentation path. */
@NullMarked
final class ReleaseScreenPassResources {
  private ReleaseScreenPassResources() {}

  static void check(ClientGameTestContext context) {
    if (!"none".equals(System.getProperty("cbbg.test.compat", "none"))) return;
    var repository = context.computeOnClient(Minecraft::getResourcePackRepository);
    Path directory =
        context.computeOnClient(
            client -> client.getResourcePackDirectory().resolve("cbbg-blit-replacement"));
    if (Files.exists(directory)) throw new AssertionError("Test pack already exists");
    try (var pack =
        new TestPack(context, repository, List.copyOf(repository.getSelectedIds()), directory)) {
      pack.install();
      reload(context);
      ReleaseLifecycleGameTest.awaitPresentation(context);
      ReleasePixels.check(context, true, Boolean.FALSE);
    } catch (IOException failure) {
      throw new AssertionError("Cannot remove blit replacement pack", failure);
    }
    ReleaseLifecycleGameTest.awaitPresentation(context);
    ReleasePixels.check(context, true, Boolean.TRUE);
  }

  private record TestPack(
      ClientGameTestContext context,
      PackRepository repository,
      List<String> selected,
      Path directory)
      implements Closeable {
    void install() {
      context.runOnClient(
          client -> {
            try {
              Path shader = directory.resolve("assets/minecraft/shaders/core/blit_screen.fsh");
              Files.createDirectories(Objects.requireNonNull(shader.getParent()));
              int format =
                  SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);
              Files.writeString(
                  directory.resolve("pack.mcmeta"),
                  "{\"pack\":{\"pack_format\":"
                      + format
                      + ",\"description\":\"CBBG blit replacement test\"}}");
              try (var resource =
                  client
                      .getResourceManager()
                      .getResourceOrThrow(
                          ResourceLocation.withDefaultNamespace("shaders/core/blit_screen.fsh"))
                      .open()) {
                Files.copy(resource, shader);
              }
              repository.reload();
              String id =
                  repository.getAvailableIds().stream()
                      .filter(candidate -> candidate.endsWith("cbbg-blit-replacement"))
                      .findFirst()
                      .orElseThrow();
              if (!repository.addPack(id)) throw new AssertionError("Cannot select test pack");
            } catch (IOException failure) {
              throw new AssertionError("Cannot create blit replacement pack", failure);
            }
          });
    }

    @Override
    public void close() throws IOException {
      context.runOnClient(client -> repository.setSelected(selected));
      reload(context);
      if (Files.exists(directory)) {
        try (var paths = Files.walk(directory)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
      }
      context.runOnClient(client -> repository.reload());
    }
  }

  private static void reload(ClientGameTestContext context) {
    var reload = context.computeOnClient(Minecraft::reloadResourcePacks);
    context.waitFor(client -> reload.isDone() && client.getOverlay() == null, 600);
    reload.join();
  }
}
