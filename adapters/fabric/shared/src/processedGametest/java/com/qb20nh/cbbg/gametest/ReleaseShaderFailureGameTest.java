package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Forces the packaged renderer to compile a malformed real resource and checks fallback/recovery.
 */
@NullMarked
public final class ReleaseShaderFailureGameTest implements FabricClientGameTest {
  private static final int WAIT_TICKS = 600;
  private static final String PACK_FOLDER = "cbbg-release-shader-failure";
  private static final String SENTINEL = "CBBG deliberate shader compilation failure";
  private static final Identifier DITHER_SHADER =
      Identifier.fromNamespaceAndPath("cbbg", "shaders/core/cbbg_dither.fsh");

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    JsonObject original = settings();
    PackRepository repository = context.computeOnClient(Minecraft::getResourcePackRepository);
    List<String> originalPacks = List.copyOf(repository.getSelectedIds());
    Path packDirectory =
        context.computeOnClient(client -> client.getResourcePackDirectory().resolve(PACK_FOLDER));
    boolean[] packInstalled = {false};
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      try {
        command(context, "mode set disabled");
        command(context, "stbn size 16");
        command(context, "stbn depth 8");
        command(context, "stbn seed 42");
        command(context, "format set rgba32f");
        command(context, "mode set enabled");
        waitForMode(context, "ENABLED");
        waitForFormat(context, "RGBA32F");
        context.waitFor(client -> ReleaseShaderFailureTarget.render(client) != null, WAIT_TICKS);
        if (context.computeOnClient(ReleaseShaderFailureTarget::disabled)) {
          throw new AssertionError("Packaged shader failed before installing the bad resource");
        }
        captureScreenshot(context, "healthy.png");

        createMalformedPack(packDirectory);
        packInstalled[0] = true;
        String packId = installPack(context, repository);
        reloadResources(context);
        assertShaderResource(context, packId, true);

        context.runOnClient(client -> RenderSystem.getDevice().clearPipelineCache());
        captureFallbackScreenshot(context);
        context.waitFor(ReleaseShaderFailureTarget::disabled, WAIT_TICKS);
        restorePacks(context, repository, originalPacks, packDirectory);
        packInstalled[0] = false;
        assertShaderResource(context, packId, false);
        context.runOnClient(client -> RenderSystem.getDevice().clearPipelineCache());
        command(context, "mode set disabled");
        waitForMode(context, "DISABLED");
        command(context, "mode set enabled");
        waitForMode(context, "ENABLED");
        context.waitFor(client -> ReleaseShaderFailureTarget.render(client) != null, WAIT_TICKS);
        if (context.computeOnClient(ReleaseShaderFailureTarget::disabled)) {
          throw new AssertionError("Valid resource reload did not clear shader failure");
        }
        captureScreenshot(context, "recovered.png");
      } finally {
        try {
          if (packInstalled[0]) restorePacks(context, repository, originalPacks, packDirectory);
        } finally {
          restoreSettings(context, original);
        }
      }
    }
  }

  private static String installPack(ClientGameTestContext context, PackRepository repository) {
    return context.computeOnClient(
        client -> {
          repository.reload();
          String id =
              repository.getAvailableIds().stream()
                  .filter(candidate -> candidate.endsWith(PACK_FOLDER))
                  .findFirst()
                  .orElseThrow(() -> new AssertionError("Test shader pack was not discovered"));
          if (!repository.addPack(id) || !repository.getSelectedIds().contains(id)) {
            throw new AssertionError("Could not activate the test shader pack");
          }
          return id;
        });
  }

  private static void restorePacks(
      ClientGameTestContext context,
      PackRepository repository,
      List<String> originalPacks,
      Path packDirectory) {
    context.runOnClient(
        client -> {
          repository.setSelected(originalPacks);
          repository.reload();
        });
    reloadResources(context);
    deletePack(packDirectory);
    context.runOnClient(client -> repository.reload());
  }

  private static void reloadResources(ClientGameTestContext context) {
    CompletableFuture<Void> reload = context.computeOnClient(Minecraft::reloadResourcePacks);
    context.waitFor(
        client -> reload.isDone() && !ReleaseViewport.loadingOverlay(client), WAIT_TICKS);
    reload.join();
  }

  private static void assertShaderResource(
      ClientGameTestContext context, String packId, boolean malformed) {
    context.runOnClient(
        client -> {
          Resource resource =
              client
                  .getResourceManager()
                  .getResource(DITHER_SHADER)
                  .orElseThrow(() -> new AssertionError("CBBG dither shader resource is missing"));
          if (malformed != resource.sourcePackId().equals(packId)) {
            throw new AssertionError(
                "Unexpected active shader source pack: " + resource.sourcePackId());
          }
          try (var reader = resource.openAsReader()) {
            String source = reader.lines().reduce("", (left, right) -> left + right + "\n");
            if (malformed != source.contains(SENTINEL)) {
              throw new AssertionError("The active dither shader source has the wrong contents");
            }
          } catch (IOException failure) {
            throw new AssertionError("Could not read active dither shader source", failure);
          }
        });
  }

  private static void captureScreenshot(ClientGameTestContext context, String name) {
    CompletableFuture<@Nullable Void> capture = new CompletableFuture<>();
    context.runOnClient(
        client ->
            Screenshot.takeScreenshot(
                ReleaseViewport.mainTarget(client),
                1,
                image -> {
                  try (image) {
                    if (image.getWidth() <= 0 || image.getHeight() <= 0) {
                      throw new AssertionError(
                          "Screenshot returned empty pixels during shader failure");
                    }
                    Path evidence = evidence().resolve("shader-failure").resolve(name);
                    Files.createDirectories(Objects.requireNonNull(evidence.getParent()));
                    image.writeToFile(evidence);
                    capture.complete(null);
                  } catch (Throwable failure) {
                    capture.completeExceptionally(failure);
                  }
                }));
    context.waitFor(client -> capture.isDone(), 200);
    capture.join();
  }

  private static void captureFallbackScreenshot(ClientGameTestContext context) {
    PixelCapture capture =
        context.computeOnClient(
            client -> {
              RenderTarget main = ReleaseViewport.mainTarget(client);
              int width = main.width;
              int height = main.height;
              if (width <= 0 || height <= 0) {
                throw new AssertionError("Fallback screenshot has an empty main target");
              }
              GpuTexture color = Objects.requireNonNull(main.getColorTexture());
              if (!"rgba32f".equals(ReleaseAllocationFormat.actual(color))) {
                throw new AssertionError("Fallback main target is not RGBA32F");
              }
              boolean vulkan = "vulkan".equalsIgnoreCase(ReleaseBackend.identity()[0]);
              CompletableFuture<byte[]> source =
                  ReleaseWorldReadback.read(color, width * height * 16, true);
              CompletableFuture<int[]> screenshot = new CompletableFuture<>();
              Screenshot.takeScreenshot(
                  main,
                  1,
                  image -> {
                    try (image) {
                      if (image.getWidth() != width || image.getHeight() != height) {
                        throw new AssertionError(
                            "Fallback screenshot dimensions differ from main target");
                      }
                      Path path = evidence().resolve("shader-failure/fallback.png");
                      Files.createDirectories(Objects.requireNonNull(path.getParent()));
                      image.writeToFile(path);
                      screenshot.complete(image.getPixels());
                    } catch (Throwable failure) {
                      screenshot.completeExceptionally(failure);
                    }
                  });
              return new PixelCapture(width, height, vulkan, source, screenshot);
            });
    context.waitFor(client -> capture.source().isDone() && capture.screenshot().isDone(), 200);
    assertFallbackScreenshot(capture);
  }

  private static void assertFallbackScreenshot(PixelCapture capture) {
    // Force RGBA32F before capture so the reference does not need a half-float tolerance.
    ByteBuffer source = ByteBuffer.wrap(capture.source().join()).order(ByteOrder.nativeOrder());
    int[] screenshot = capture.screenshot().join();
    int width = capture.width();
    int height = capture.height();
    if (source.remaining() != width * height * 16 || screenshot.length != width * height) {
      throw new AssertionError("Fallback screenshot readback has unexpected dimensions");
    }
    int boundaryChannels = 0;
    int vulkanAlternativeChannels = 0;
    for (int y = 0; y < height; y++) {
      int gpuY = height - 1 - y;
      for (int x = 0; x < width; x++) {
        int sourceOffset = (gpuY * width + x) * 16;
        int observed = screenshot[y * width + x];
        if (observed >>> 24 != 255) {
          throw new AssertionError("Fallback screenshot alpha is not opaque at " + x + "," + y);
        }
        for (int channel = 0; channel < 3; channel++) {
          float value = source.getFloat(sourceOffset + channel * 4);
          if (!Float.isFinite(value)) {
            throw new AssertionError("Fallback main target contains nonfinite color data");
          }
          int quantized = quantize(value);
          int actual = observed >>> (16 - channel * 8) & 255;
          if (actual == quantized) continue;
          if (capture.vulkan() && vulkanPermitted(value, actual)) {
            vulkanAlternativeChannels++;
            continue;
          }
          if (!capture.vulkan() && Math.abs(actual - quantized) == 1 && halfByteBoundary(value)) {
            boundaryChannels++;
            continue;
          }
          throw new AssertionError(
              "Fallback screenshot differs from same-draw main target at "
                  + x
                  + ","
                  + y
                  + ":"
                  + channel
                  + " expected="
                  + quantized
                  + " actual="
                  + actual
                  + " source="
                  + value);
        }
      }
    }
    try {
      Files.writeString(
          evidence().resolve("shader-failure/fallback-boundaries.json"),
          "{\"comparedChannels\":"
              + (width * height * 3)
              + ",\"halfByteBoundaryChannels\":"
              + boundaryChannels
              + ",\"vulkanAlternativeRoundingChannels\":"
              + vulkanAlternativeChannels
              + "}\n");
    } catch (IOException failure) {
      throw new AssertionError("Could not record fallback boundary checks", failure);
    }
  }

  private static boolean halfByteBoundary(float value) {
    double scaled = Math.clamp((double) value, 0.0, 1.0) * 255.0;
    return Math.abs(scaled - (Math.floor(scaled) + 0.5)) <= Math.ulp(value) * 255.0;
  }

  private static boolean vulkanPermitted(float value, int actual) {
    double scaled = Math.clamp((double) value, 0.0, 1.0) * 255.0;
    return actual == (int) Math.floor(scaled) || actual == (int) Math.ceil(scaled);
  }

  private static int quantize(float value) {
    if (!Float.isFinite(value)) {
      throw new AssertionError("Fallback main target contains nonfinite color data");
    }
    return (int) Math.floor(Math.clamp(value, 0.0f, 1.0f) * 255.0 + 0.5);
  }

  private static void command(ClientGameTestContext context, String suffix) {
    context.runOnClient(
        client -> Objects.requireNonNull(client.getConnection()).sendCommand("cbbg " + suffix));
  }

  private static void waitForMode(ClientGameTestContext context, String mode) {
    context.waitFor(client -> mode.equals(settings().get("mode").getAsString()), WAIT_TICKS);
  }

  private static void restoreSettings(ClientGameTestContext context, JsonObject original) {
    command(context, "mode set disabled");
    waitForMode(context, "DISABLED");
    command(context, "stbn size " + original.get("stbnSize").getAsInt());
    command(context, "stbn depth " + original.get("stbnDepth").getAsInt());
    command(context, "stbn seed " + original.get("stbnSeed").getAsLong());
    String originalFormat =
        original.get("pixelFormat").getAsString().toLowerCase(java.util.Locale.ROOT);
    command(context, "format set " + originalFormat);
    command(
        context,
        "mode set " + original.get("mode").getAsString().toLowerCase(java.util.Locale.ROOT));
    context.waitFor(
        client -> {
          JsonObject restored = settings();
          return original.entrySet().stream()
              .allMatch(entry -> entry.getValue().equals(restored.get(entry.getKey())));
        },
        WAIT_TICKS);
  }

  private static void waitForFormat(ClientGameTestContext context, String format) {
    context.waitFor(
        client -> format.equalsIgnoreCase(settings().get("pixelFormat").getAsString()), WAIT_TICKS);
  }

  private static void createMalformedPack(Path directory) {
    if (Files.exists(directory)) {
      throw new AssertionError("Shader-failure pack directory already exists: " + directory);
    }
    int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
    Path shader = directory.resolve("assets/cbbg/shaders/core/cbbg_dither.fsh");
    try {
      Files.createDirectories(Objects.requireNonNull(shader.getParent()));
      Files.writeString(
          directory.resolve("pack.mcmeta"),
          "{\"pack\":{\"pack_format\":"
              + format
              + ",\"min_format\":"
              + format
              + ",\"max_format\":"
              + format
              + ",\"description\":\"CBBG shader failure fixture\"}}\n",
          StandardCharsets.UTF_8);
      Files.writeString(
          shader,
          "#version 150\n#error " + SENTINEL + "\nvoid main() {}\n",
          StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new AssertionError("Could not create malformed shader resource pack", failure);
    }
  }

  private static void deletePack(Path directory) {
    if (!Files.exists(directory)) return;
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException failure) {
      throw new AssertionError("Could not remove the shader-failure test pack", failure);
    }
  }

  private static Path evidence() {
    String location = System.getProperty("cbbg.test.evidence");
    if (location == null || location.isBlank()) {
      throw new AssertionError("cbbg.test.evidence is required for shader-failure screenshots");
    }
    return Path.of(location);
  }

  private static JsonObject settings() {
    Path config = FabricLoader.getInstance().getConfigDir().resolve("cbbg.json");
    try (var reader = Files.newBufferedReader(config)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (IOException failure) {
      throw new AssertionError("Could not read persisted CBBG settings", failure);
    }
  }

  private record PixelCapture(
      int width,
      int height,
      boolean vulkan,
      CompletableFuture<byte[]> source,
      CompletableFuture<int[]> screenshot) {}
}
