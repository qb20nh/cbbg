package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import org.jspecify.annotations.NullMarked;

/** Checks window pixels and screenshots with replacement shaders and after reload. */
@NullMarked
final class ReleaseWindowResources {
  private ReleaseWindowResources() {}

  static void check(ClientGameTestContext context) {
    var repository = context.computeOnClient(Minecraft::getResourcePackRepository);
    Path directory =
        context.computeOnClient(
            client -> client.getResourcePackDirectory().resolve("cbbg-window-shader-test"));
    if (Files.exists(directory)) throw new AssertionError("Test pack already exists");
    try (var pack =
        new TestPack(context, repository, List.copyOf(repository.getSelectedIds()), directory)) {
      pack.install();
      reload(context);
      context.runOnClient(
          client -> {
            String source =
                client
                    .getResourceManager()
                    .getResourceOrThrow(
                        Identifier.fromNamespaceAndPath("cbbg", "shaders/core/cbbg_dither.fsh"))
                    .sourcePackId();
            if (!source.endsWith("cbbg-window-shader-test")) {
              throw new AssertionError("Replacement shader pack was not selected: " + source);
            }
          });
      for (boolean demo : new boolean[] {false, true}) {
        ReleaseLifecycleGameTest.command(context, "mode set " + (demo ? "demo" : "enabled"));
        ReleaseLifecycleGameTest.awaitPresentation(context);
        ReleasePixels.checkReplacement(context, demo);
      }
    } catch (IOException failure) {
      throw new AssertionError("Cannot remove replacement shader pack", failure);
    }
    ReleaseLifecycleGameTest.awaitPresentation(context);
    ReleasePixels.check(context, true);
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
              Files.createDirectories(directory);
              var version =
                  SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
              JsonArray format = new JsonArray();
              format.add(version.major());
              format.add(version.minor());
              JsonObject pack = new JsonObject();
              pack.add("min_format", format);
              pack.add("max_format", format);
              pack.addProperty("description", "CBBG window shader test");
              JsonObject metadata = new JsonObject();
              metadata.add("pack", pack);
              Files.writeString(directory.resolve("pack.mcmeta"), metadata.toString());
              for (String name : List.of("cbbg_dither", "cbbg_demo")) {
                String resource = "shaders/core/" + name + ".fsh";
                String original;
                try (var stream =
                    client
                        .getResourceManager()
                        .getResourceOrThrow(Identifier.fromNamespaceAndPath("cbbg", resource))
                        .open()) {
                  original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                String output = "vec4(clamp(color.rgb, 0.0, 1.0), color.a)";
                if (!original.contains(output))
                  throw new AssertionError("Unknown dither output shader");
                String modified =
                    original.replace(
                        output,
                        "vec4(vec3(0.125, 0.25, 0.75) + clamp(color.rgb, 0.0, 1.0) * 0.00001, "
                            + "0.375 + clamp(color.a, 0.0, 1.0) * 0.00001)");
                Path destination = directory.resolve("assets/cbbg/" + resource);
                Files.createDirectories(Objects.requireNonNull(destination.getParent()));
                Files.writeString(destination, modified);
              }
              repository.reload();
              String id =
                  repository.getAvailableIds().stream()
                      .filter(candidate -> candidate.endsWith("cbbg-window-shader-test"))
                      .findFirst()
                      .orElseThrow();
              if (!repository.addPack(id)) throw new AssertionError("Cannot select test pack");
            } catch (IOException failure) {
              throw new AssertionError("Cannot create replacement shader pack", failure);
            }
          });
    }

    @Override
    public void close() throws IOException {
      context.runOnClient(client -> repository.setSelected(selected));
      reload(context);
      if (Files.exists(directory)) {
        try (var paths = Files.walk(directory)) {
          for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(item);
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
