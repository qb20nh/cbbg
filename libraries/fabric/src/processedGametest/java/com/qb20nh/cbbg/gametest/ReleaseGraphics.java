package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.slf4j.LoggerFactory;

@NullMarked
final class ReleaseGraphics {
  private ReleaseGraphics() {}

  static void check(ClientGameTestContext context) {
    context.runOnClient(
        client -> {
          String expected = Objects.requireNonNull(System.getProperty("cbbg.test.backend"));
          String[] identity = ReleaseBackend.identity();
          var loader = FabricLoader.getInstance();
          var library =
              loader
                  .getModContainer("cbbg_lib")
                  .orElseThrow(() -> new AssertionError("Packaged CBBG library is missing"));
          long libraries =
              loader.getAllMods().stream()
                  .filter(mod -> mod.getMetadata().getId().equals("cbbg_lib"))
                  .count();
          if (libraries != 1) {
            throw new AssertionError(
                "Expected exactly one resolved CBBG library, got " + libraries);
          }
          if (loader.isModLoaded("cbbg") || !identity[0].equalsIgnoreCase(expected)) {
            throw new AssertionError(
                "Library-only fixture loaded CBBG or the wrong graphics backend");
          }
          for (String key : new String[] {"main", "client", "server"}) {
            if (loader.getEntrypointContainers(key, Object.class).stream()
                .anyMatch(entry -> entry.getProvider().getMetadata().getId().equals("cbbg_lib"))) {
              throw new AssertionError("CBBG library registered an application entrypoint: " + key);
            }
          }
          LoggerFactory.getLogger("cbbg-renderer-test")
              .info("Readback backend={} GPU={} driver={}", identity[0], identity[1], identity[2]);
          JsonObject record = new JsonObject();
          record.addProperty("backend", expected);
          record.addProperty("modVersion", library.getMetadata().getVersion().getFriendlyString());
          record.addProperty("device", identity[1]);
          record.addProperty("driver", identity[2]);
          if (expected.equals("opengl")) {
            var capabilities = GL.getCapabilities();
            record.addProperty("version", GL11.glGetString(GL11.GL_VERSION));
            record.addProperty("major", GL11.glGetInteger(GL30.GL_MAJOR_VERSION));
            record.addProperty("minor", GL11.glGetInteger(GL30.GL_MINOR_VERSION));
            record.addProperty("flags", GL11.glGetInteger(GL30.GL_CONTEXT_FLAGS));
            int profile =
                capabilities.OpenGL32 ? GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK) : 0;
            record.addProperty("profileMask", profile);
            record.addProperty(
                "profile",
                (profile & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0 ? "core" : "compatibility");
            record.addProperty("glslVersion", GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION));
            record.addProperty("directStateAccess", capabilities.GL_ARB_direct_state_access);
            record.addProperty("bufferStorage", capabilities.GL_ARB_buffer_storage);
          }
          try {
            Path directory =
                Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")));
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("graphics-context.json"), record + "\n");
          } catch (Exception error) {
            throw new AssertionError("Cannot save graphics backend results", error);
          }
        });
  }
}
