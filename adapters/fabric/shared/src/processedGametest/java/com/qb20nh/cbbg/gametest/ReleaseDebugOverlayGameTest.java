package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;

/** Checks the registered, visible F3 entry against live world/render state. */
@NullMarked
public final class ReleaseDebugOverlayGameTest implements FabricClientGameTest {
  private static final Identifier ID = Identifier.fromNamespaceAndPath("cbbg", "cbbg");
  private static final Pattern NOISE = Pattern.compile("stbn=(\\d+)/(\\d+)");

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    JsonObject original = settings();
    FabricClientCommandSource source = ReleaseGenerationGameTest.silentSource();
    boolean visible = context.computeOnClient(client -> client.debugEntries.isOverlayVisible());
    DebugScreenEntryStatus status =
        context.computeOnClient(client -> client.debugEntries.getStatus(ID));
    context.runOnClient(
        client -> {
          for (var profile : DebugScreenEntries.PROFILES.values()) {
            if (profile.get(ID) != DebugScreenEntryStatus.IN_OVERLAY) {
              throw new AssertionError("Packaged CBBG is absent from a default F3 profile");
            }
          }
          client.debugEntries.setStatus(ID, DebugScreenEntryStatus.IN_OVERLAY);
          client.debugEntries.setOverlayVisible(true);
        });
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      CommandDispatcher<FabricClientCommandSource> dispatcher =
          Objects.requireNonNull(
              context.computeOnClient(client -> ClientCommands.getActiveDispatcher()));
      try {
        for (String mode : new String[] {"enabled", "disabled", "demo"}) {
          context.runOnClient(client -> command(dispatcher, source, "mode set " + mode));
          if (!mode.equals("disabled")) {
            context.waitFor(client -> ReleaseWorldTarget.noise() != null, 600);
          }
          context.waitTicks(5);
          context.runOnClient(
              client -> check(client, mode, settings().get("stbnDepth").getAsInt()));
        }
      } finally {
        context.runOnClient(
            client ->
                command(
                    dispatcher,
                    source,
                    "mode set " + original.get("mode").getAsString().toLowerCase(Locale.ROOT)));
      }
    } finally {
      context.runOnClient(
          client -> {
            client.debugEntries.setStatus(ID, status);
            client.debugEntries.setOverlayVisible(visible);
          });
    }
  }

  private static void check(Minecraft client, String mode, int depth) {
    if (!client.debugEntries.isOverlayVisible()
        || client.debugEntries.getStatus(ID) != DebugScreenEntryStatus.IN_OVERLAY) {
      throw new AssertionError("CBBG F3 entry is not visible");
    }
    List<String> lines = output(client);
    if (lines.size() != 2) throw new AssertionError("Incomplete CBBG F3 entry: " + lines);
    String text = String.join("\n", lines);
    String upper = mode.toUpperCase(Locale.ROOT);
    String expectedMain =
        ReleaseDebugExpectation.main(
            Objects.requireNonNull(ReleaseWorldTarget.main(client).getColorTexture()), mode);
    if (!text.contains("mode=" + upper + " (user=" + upper + ")")
        || !text.contains("main=" + expectedMain)
        || !text.contains(" lm=")
        || !text.contains(" fb=")
        || !text.contains(" srgb=")
        || !text.contains("iris=0")
        || !text.contains("dis=0")) {
      throw new AssertionError("Wrong live CBBG F3 state: " + text);
    }
    var match = NOISE.matcher(text);
    if (!match.find()) throw new AssertionError("Missing F3 noise frame: " + text);
    int frame = Integer.parseInt(Objects.requireNonNull(match.group(1)));
    int frames = Integer.parseInt(Objects.requireNonNull(match.group(2)));
    int expectedFrames =
        mode.equals("disabled") ? ReleaseDebugExpectation.disabledFrames(depth) : depth;
    if (frames != expectedFrames || (frames > 0 && (frame < 0 || frame >= frames))) {
      throw new AssertionError("Wrong live F3 noise sequence: " + text);
    }
    try {
      Path path =
          Path.of(
              Objects.requireNonNull(System.getProperty("cbbg.test.evidence")),
              "debug-" + mode + ".txt");
      Files.writeString(path, text + "\n");
    } catch (java.io.IOException failure) {
      throw new AssertionError("Cannot retain F3 output", failure);
    }
  }

  static List<String> output(Minecraft client) {
    List<String> lines = new ArrayList<>();
    DebugScreenDisplayer displayer =
        (DebugScreenDisplayer)
            Proxy.newProxyInstance(
                DebugScreenDisplayer.class.getClassLoader(),
                new Class<?>[] {DebugScreenDisplayer.class},
                (proxy, method, args) -> {
                  if (!method.getName().equals("addLine")) throw new AssertionError(method);
                  lines.add((String) Objects.requireNonNull(args)[0]);
                  return null;
                });
    var entry = DebugScreenEntries.getEntry(ID);
    if (entry == null) throw new AssertionError("Packaged CBBG F3 entry is missing");
    entry.display(displayer, client.level, null, null);
    return List.copyOf(lines);
  }

  private static JsonObject settings() {
    try (var reader =
        Files.newBufferedReader(FabricLoader.getInstance().getConfigDir().resolve("cbbg.json"))) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception failure) {
      throw new AssertionError("Cannot read packaged CBBG settings", failure);
    }
  }

  private static void command(
      CommandDispatcher<FabricClientCommandSource> dispatcher,
      FabricClientCommandSource source,
      String suffix) {
    ReleaseGenerationGameTest.command(dispatcher, source, suffix);
  }
}
