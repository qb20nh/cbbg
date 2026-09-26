package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ReleaseCommandsGameTest implements FabricClientGameTest {
  private static final int WAIT_TICKS = 600;

  @Override
  public void runTest(ClientGameTestContext context) {
    JsonObject original = ReleaseClient.settings().deepCopy();
    FabricClientCommandSource source = ReleaseClient.silentCommandSource();

    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      try {
        context.runOnClient(
            client -> {
              execute(source, "mode set enabled", 1);
              execute(source, "format set rgba16f", 1);
            });
        ReleaseClient.awaitFormat(context, GpuFormat.RGBA16_FLOAT);
        ReleaseClient.awaitDrawAfter(context, ProcessedRenderObservations.draws());

        GpuTexture oldColor =
            context.computeOnClient(
                client -> {
                  execute(source, "help", 1);
                  execute(source, "mode set demo", 1);
                  execute(source, "mode set unknown", 0);
                  assertSaved("mode", "DEMO");

                  GpuTexture color = client.gameRenderer.mainRenderTarget().getColorTexture();
                  execute(source, "format set rgba32f", 1);
                  execute(source, "format set rgba8", 0);
                  execute(source, "format set unknown", 0);
                  assertSaved("pixelFormat", "RGBA32F");

                  execute(source, "stbn size 16", 1);
                  execute(source, "stbn size 17", 0);
                  execute(source, "stbn depth 8", 1);
                  execute(source, "stbn depth 9", 0);
                  execute(source, "stbn seed 42", 1);
                  execute(source, "notification chat false", 1);
                  execute(source, "notification toast false", 1);
                  JsonObject settings = ReleaseClient.settings();
                  if (settings.get("stbnSize").getAsInt() != 16
                      || settings.get("stbnDepth").getAsInt() != 8
                      || settings.get("stbnSeed").getAsLong() != 42
                      || settings.get("notifyChat").getAsBoolean()
                      || settings.get("notifyToast").getAsBoolean()) {
                    throw new AssertionError("Command settings were not persisted: " + settings);
                  }
                  return color;
                });

        ReleaseClient.awaitFormat(context, GpuFormat.RGBA32_FLOAT);
        ReleaseClient.awaitDrawAfter(context, ProcessedRenderObservations.draws());
        long beforeGeneration =
            context.computeOnClient(
                client -> {
                  if (!Objects.requireNonNull(oldColor).isClosed()) {
                    throw new AssertionError(
                        "Format replacement retained the old color attachment");
                  }
                  GpuTextureView oldNoiseBeforeGenerate =
                      ProcessedRenderObservations.lastDitherNoise();
                  if (oldNoiseBeforeGenerate == null
                      || oldNoiseBeforeGenerate.texture().isClosed()) {
                    throw new AssertionError("No live noise texture before forced generation");
                  }
                  execute(source, "stbn generate", 1);
                  if (!oldNoiseBeforeGenerate.texture().isClosed()) {
                    throw new AssertionError("Forced generation retained the old noise texture");
                  }
                  return ProcessedRenderObservations.draws();
                });
        ReleaseClient.awaitCache(context, 16, 8, 42);
        ReleaseClient.awaitDrawAfter(context, beforeGeneration);
        context.runOnClient(client -> execute(source, "mode set disabled", 1));
        ReleaseClient.awaitFormat(context, GpuFormat.RGBA8_UNORM);
        ReleaseClient.assertNoDraws(context);
        context.runOnClient(
            client -> {
              if (ProcessedRenderObservations.lastDitherNoise() != null
                  && !ProcessedRenderObservations.lastDitherNoise().texture().isClosed()) {
                throw new AssertionError("Disable command retained the active noise texture");
              }
              execute(source, "stbn generate", 1);
            });
        ReleaseClient.awaitCache(context, 16, 8, 42);
        ReleaseClient.assertNoDraws(context);
        assertSaved("mode", "DISABLED");
        context.runOnClient(client -> execute(source, "mode set enabled", 1));
        ReleaseClient.awaitFormat(context, GpuFormat.RGBA32_FLOAT);
        long beforeReenabledDraw = ProcessedRenderObservations.draws();
        ReleaseClient.awaitDrawAfter(context, beforeReenabledDraw);
      } finally {
        restore(context, source, original);
      }
    }
  }

  private static void restore(
      ClientGameTestContext context, FabricClientCommandSource source, JsonObject original) {
    context.runOnClient(
        client -> {
          execute(source, "mode set disabled", 1);
          execute(
              source,
              "format set " + original.get("pixelFormat").getAsString().toLowerCase(Locale.ROOT),
              1);
          execute(source, "stbn size " + original.get("stbnSize").getAsInt(), 1);
          execute(source, "stbn depth " + original.get("stbnDepth").getAsInt(), 1);
          execute(source, "stbn seed " + original.get("stbnSeed").getAsLong(), 1);
          execute(source, "notification chat " + original.get("notifyChat").getAsBoolean(), 1);
          execute(source, "notification toast " + original.get("notifyToast").getAsBoolean(), 1);
          execute(
              source, "mode set " + original.get("mode").getAsString().toLowerCase(Locale.ROOT), 1);
        });
    String expectedFormat = original.get("pixelFormat").getAsString();
    GpuFormat gpuFormat =
        switch (expectedFormat) {
          case "RGBA32F" -> GpuFormat.RGBA32_FLOAT;
          case "RGBA16F" -> GpuFormat.RGBA16_FLOAT;
          default -> throw new AssertionError("Unsupported saved CBBG format: " + expectedFormat);
        };
    boolean disabled = "DISABLED".equals(original.get("mode").getAsString());
    ReleaseClient.awaitFormat(context, disabled ? GpuFormat.RGBA8_UNORM : gpuFormat);
    context.waitFor(client -> original.equals(ReleaseClient.settings()), WAIT_TICKS);
  }

  private static void assertSaved(String key, String expected) {
    if (!ReleaseClient.settings().get(key).getAsString().equals(expected)) {
      throw new AssertionError("Command did not save " + key + "=" + expected);
    }
  }

  private static void execute(FabricClientCommandSource source, String command, int expected) {
    var dispatcher = ClientCommands.getActiveDispatcher();
    if (dispatcher == null) {
      throw new AssertionError("No active client command dispatcher");
    }
    try {
      int result = dispatcher.execute("cbbg " + command, source);
      if (result != expected) {
        throw new AssertionError("Unexpected command result for '" + command + "': " + result);
      }
    } catch (CommandSyntaxException failure) {
      throw new AssertionError("Command failed: " + command, failure);
    }
  }
}
