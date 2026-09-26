package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntPredicate;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.slf4j.LoggerFactory;

/** External observations of a packaged CBBG client; no production CBBG classes are linked. */
@NullMarked
final class ReleaseClient {
  private static final int WAIT_TICKS = 600;

  private ReleaseClient() {}

  static FabricClientCommandSource silentCommandSource() {
    return (FabricClientCommandSource)
        Proxy.newProxyInstance(
            FabricClientCommandSource.class.getClassLoader(),
            new Class<?>[] {FabricClientCommandSource.class},
            (proxy, method, args) -> {
              if (method.getName().equals("sendFeedback") || method.getName().equals("sendError")) {
                return null;
              }
              throw new AssertionError("Unexpected command source call: " + method);
            });
  }

  static void noiseSetting(ClientGameTestContext context, String name, long value) {
    command(context, "stbn " + name + " " + value);
    String key = "stbn" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    context.waitFor(client -> settings().get(key).getAsLong() == value, WAIT_TICKS);
  }

  static void disableNoise(ClientGameTestContext context) {
    GpuTextureView noise =
        context.computeOnClient(client -> ProcessedRenderObservations.lastDitherNoise());
    GpuTextureView output =
        context.computeOnClient(client -> ProcessedRenderObservations.lastDitherOutput());
    command(context, "mode set disabled");
    context.waitFor(
        client ->
            "disabled".equalsIgnoreCase(settings().get("mode").getAsString())
                && (noise == null || noise.texture().isClosed())
                && (output == null || output.texture().isClosed()),
        WAIT_TICKS);
    assertNoDraws(context);
  }

  static boolean restoreNoiseSettings(ClientGameTestContext context, JsonObject original) {
    disableNoise(context);
    noiseSetting(context, "size", original.get("stbnSize").getAsLong());
    noiseSetting(context, "depth", original.get("stbnDepth").getAsLong());
    noiseSetting(context, "seed", original.get("stbnSeed").getAsLong());
    String mode = original.get("mode").getAsString();
    command(context, "mode set " + mode.toLowerCase(Locale.ROOT));
    context.waitFor(client -> original.equals(settings()), WAIT_TICKS);
    return !mode.equalsIgnoreCase("disabled");
  }

  static void blitNoise(TextureTarget target, GpuTextureView noise, String label) {
    try (RenderPass pass =
        RenderSystem.getDevice()
            .createCommandEncoder()
            .createRenderPass(
                () -> label,
                Objects.requireNonNull(target.getColorTextureView()),
                Optional.empty())) {
      pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
      RenderSystem.bindDefaultUniforms(pass);
      pass.setUniform(
          "InSampler", noise, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
      pass.draw(3, 1, 0, 0);
    }
  }

  static PixelCounts centerPixels(NativeImage image, IntPredicate matches) {
    int matching = 0;
    int total = 0;
    for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
      for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
        total++;
        if (matches.test(image.getPixel(x, y))) matching++;
      }
    }
    return new PixelCounts(matching, total);
  }

  record PixelCounts(int matching, int total) {}

  static Path config() {
    return FabricLoader.getInstance().getConfigDir().resolve("cbbg.json");
  }

  static Path cache() {
    return FabricLoader.getInstance().getGameDir().resolve(".cbbg");
  }

  static void checkArtifactAndBackend(ClientGameTestContext context) {
    var mod =
        FabricLoader.getInstance()
            .getModContainer("cbbg")
            .orElseThrow(() -> new AssertionError("Packaged CBBG mod is not loaded"));
    String version = mod.getMetadata().getVersion().getFriendlyString();
    if (!version.contains("mc26.3")) {
      throw new AssertionError("Unexpected CBBG version: " + version);
    }
    List<String> requestedMods =
        List.of(
            Objects.requireNonNull(System.getProperty("cbbg.test.compat", "none")).split("\\+"));
    for (String id : List.of("sodium", "sulkan", "iris", "chatpatches", "immediatelyfast")) {
      boolean expected =
          requestedMods.contains(id)
              || (id.equals("sodium")
                  && (requestedMods.contains("iris") || requestedMods.contains("sulkan")));
      if (FabricLoader.getInstance().isModLoaded(id) != expected) {
        throw new AssertionError(id + " presence does not match the requested fixture");
      }
    }
    context.runOnClient(
        client -> {
          var info = RenderSystem.getDevice().getDeviceInfo();
          LoggerFactory.getLogger("cbbg-test")
              .info(
                  "Readback backend={} GPU={} driver={}",
                  info.backendName(),
                  info.name(),
                  info.driverInfo());
          String requested = System.getProperty("cbbg.test.backend");
          if (requested == null || !requested.equalsIgnoreCase(info.backendName())) {
            throw new AssertionError(
                "Requested backend " + requested + ", got " + info.backendName());
          }
          JsonObject observed = new JsonObject();
          observed.addProperty("backend", info.backendName().toLowerCase(Locale.ROOT));
          observed.addProperty("gpu", info.name());
          observed.addProperty("driver", info.driverInfo());
          observed.addProperty("modVersion", version);
          if (info.backendName().equalsIgnoreCase("opengl")) {
            var caps = GL.getCapabilities();
            observed.addProperty("version", GL11C.glGetString(GL11C.GL_VERSION));
            if (caps.OpenGL30) {
              observed.addProperty("major", GL11C.glGetInteger(GL30C.GL_MAJOR_VERSION));
              observed.addProperty("minor", GL11C.glGetInteger(GL30C.GL_MINOR_VERSION));
              observed.addProperty("flags", GL11C.glGetInteger(GL30C.GL_CONTEXT_FLAGS));
            }
            if (caps.OpenGL32) {
              int mask = GL11C.glGetInteger(GL32C.GL_CONTEXT_PROFILE_MASK);
              observed.addProperty("profileMask", mask);
              observed.addProperty(
                  "profile",
                  (mask & GL32C.GL_CONTEXT_CORE_PROFILE_BIT) != 0
                      ? "core"
                      : (mask & GL32C.GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0
                          ? "compatibility"
                          : "unknown");
            }
            observed.addProperty(
                "directStateAccess", caps.OpenGL45 || caps.GL_ARB_direct_state_access);
            observed.addProperty("bufferStorage", caps.OpenGL44 || caps.GL_ARB_buffer_storage);
            observed.addProperty("textureStorage", caps.OpenGL42 || caps.GL_ARB_texture_storage);
            observed.addProperty("computeShader", caps.OpenGL43 || caps.GL_ARB_compute_shader);
          }
          try {
            Path evidence = evidence();
            Files.createDirectories(evidence);
            Files.writeString(evidence.resolve("graphics-context.json"), observed + "\n");
          } catch (IOException failure) {
            throw new AssertionError("Could not record graphics context", failure);
          }
        });
  }

  static void command(ClientGameTestContext context, String suffix) {
    context.runOnClient(
        client -> {
          if (client.getConnection() == null) {
            throw new AssertionError("No client connection for /cbbg " + suffix);
          }
          client.getConnection().sendCommand("cbbg " + suffix);
        });
  }

  static JsonObject settings() {
    try (var reader = Files.newBufferedReader(config())) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception failure) {
      throw new AssertionError("Could not read saved CBBG settings", failure);
    }
  }

  static void assertSettings(String mode, String format, int size, int depth, long seed) {
    JsonObject saved = settings();
    if (!mode.equals(saved.get("mode").getAsString())
        || !format.equals(saved.get("pixelFormat").getAsString())
        || size != saved.get("stbnSize").getAsInt()
        || depth != saved.get("stbnDepth").getAsInt()
        || seed != saved.get("stbnSeed").getAsLong()) {
      throw new AssertionError("/cbbg settings were not persisted: " + saved);
    }
  }

  static void awaitFormat(ClientGameTestContext context, GpuFormat expected) {
    context.waitFor(
        client -> {
          var target = client.gameRenderer.mainRenderTarget();
          return target != null
              && target.getColorTexture() != null
              && target.getDepthTexture() != null
              && target.getColorTexture().getFormat() == expected;
        },
        WAIT_TICKS);
    context.waitTicks(3);
    context.runOnClient(
        client -> {
          var target = client.gameRenderer.mainRenderTarget();
          if (Objects.requireNonNull(target.getColorTexture()).getFormat() != expected) {
            throw new AssertionError(
                "Expected main target "
                    + expected
                    + ", got "
                    + target.getColorTexture().getFormat());
          }
        });
  }

  static void awaitDrawAfter(ClientGameTestContext context, long count) {
    context.waitFor(client -> ProcessedRenderObservations.draws() > count, WAIT_TICKS);
  }

  static void assertNoDraws(ClientGameTestContext context) {
    context.waitTicks(3);
    long count = ProcessedRenderObservations.draws();
    context.waitTicks(5);
    if (ProcessedRenderObservations.draws() != count) {
      throw new AssertionError("CBBG pipeline drew while disabled");
    }
  }

  static Path manifest(int size, int depth) {
    return cache().resolve("stbn_" + size + "x" + size + "x" + depth + ".sha256");
  }

  static void awaitCache(ClientGameTestContext context, int size, int depth, long seed) {
    context.waitFor(client -> cacheValid(size, depth, seed), WAIT_TICKS);
    if (!cacheValid(size, depth, seed)) {
      throw new AssertionError("Invalid generated STBN cache");
    }
  }

  private static boolean cacheValid(int size, int depth, long seed) {
    try {
      List<String> lines = Files.readAllLines(manifest(size, depth));
      if (lines.size() != depth + 1 || !lines.getFirst().equals("# seed " + seed)) {
        return false;
      }
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (int frame = 0; frame < depth; frame++) {
        String[] parts = lines.get(frame + 1).trim().split("\\s+", 0);
        String name = "stbn_" + size + "x" + size + "x" + depth + "_" + frame + ".png";
        if (parts.length != 2 || !parts[1].equals(name)) return false;
        Path image = cache().resolve(name);
        byte[] bytes = Files.readAllBytes(image);
        if (!HexFormat.of().formatHex(digest.digest(bytes)).equalsIgnoreCase(parts[0])) {
          return false;
        }
        var decoded = ImageIO.read(image.toFile());
        if (decoded == null || decoded.getWidth() != size || decoded.getHeight() != size) {
          return false;
        }
      }
      return true;
    } catch (Exception incomplete) {
      return false;
    }
  }

  static void screenshot(ClientGameTestContext context, String name) {
    try {
      Path capture = context.takeScreenshot(name);
      Path evidence = evidence();
      Files.createDirectories(evidence);
      Files.copy(capture, evidence.resolve(name + ".png"), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException failure) {
      throw new AssertionError("Could not retain screenshot " + name, failure);
    }
  }

  private static Path evidence() {
    String location = System.getProperty("cbbg.test.evidence");
    if (location == null || location.isBlank()) {
      throw new AssertionError("cbbg.test.evidence is required");
    }
    return Path.of(location);
  }
}
