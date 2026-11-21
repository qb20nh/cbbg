package com.qb20nh.cbbg.parity;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Tests the packaged mod through its public behavior and the live OpenGL pipeline. */
public final class ClassicPackagedSmoke implements ClientModInitializer {
  private static final String WORLD = "cbbg-smoke";

  private enum Phase {
    TITLE,
    OPENING_WORLD,
    DITHERED,
    DISABLED,
    RESTARTED_DISABLED,
    IRIS,
    DONE
  }

  private static long presentations;
  private final boolean restart = Boolean.getBoolean("cbbg.test.restart");
  private final boolean iris = Boolean.getBoolean("cbbg.test.iris");
  private ClassicIrisSmoke irisSmoke;
  private int ticks;
  private Phase phase = Phase.TITLE;
  private long enabledStart;
  private long disabledStart;
  private int disabledSince;

  @Override
  public void onInitializeClient() {
    ClientTickEvents.END_CLIENT_TICK.register(this::tick);
  }

  public static void observePresentation() {
    presentations++;
  }

  static long presentationCount() {
    return presentations;
  }

  private void tick(Minecraft client) {
    try {
      if (++ticks > 1200) throw new AssertionError("Timed out at smoke phase " + phase);
      if (phase == Phase.TITLE
          && client.getOverlay() == null
          && client.screen instanceof TitleScreen title) {
        checkInstallation();
        if (restart) {
          if (!iris) {
            requireFormat(client, GL11.GL_RGBA8);
            if (presentations != 0)
              throw new AssertionError("Dither applied before opening the saved world");
          }
          client.createWorldOpenFlows().loadLevel(title, WORLD);
        } else {
          createWorld(client);
        }
        phase = Phase.OPENING_WORLD;
      } else if (phase == Phase.OPENING_WORLD && client.player != null && client.screen == null) {
        enabledStart = presentations;
        disabledSince = ticks;
        if (iris) {
          irisSmoke = new ClassicIrisSmoke(restart, ticks, presentations);
          phase = Phase.IRIS;
        } else {
          phase = restart ? Phase.RESTARTED_DISABLED : Phase.DITHERED;
        }
      } else if (phase == Phase.IRIS) {
        if (irisSmoke.tick(client, ticks, presentations)) {
          phase = Phase.DONE;
          client.stop();
        }
      } else if (phase == Phase.RESTARTED_DISABLED && ticks >= disabledSince + 6) {
        requireFormat(client, GL11.GL_RGBA8);
        if (presentations != 0)
          throw new AssertionError("Dither applied after restarting disabled");
        capture(client, "restart-disabled.png");
        System.out.println("CBBG_CLASSIC_RESTORED_DISABLED format=RGBA8 presentations=0");
        setMode(client, "enabled");
        phase = Phase.DITHERED;
      } else if (phase == Phase.DITHERED && presentations >= enabledStart + 10) {
        requireFormat(client, GL30.GL_RGBA16F);
        if (restart) {
          capture(client, "restart-enabled.png");
          System.out.println("CBBG_CLASSIC_RESTART_PASS presentations=" + presentations);
          phase = Phase.DONE;
          client.stop();
        } else {
          capture(client, "enabled.png");
          setMode(client, "disabled");
          disabledStart = presentations;
          disabledSince = ticks;
          phase = Phase.DISABLED;
        }
      } else if (phase == Phase.DISABLED && ticks >= disabledSince + 6) {
        requireFormat(client, GL11.GL_RGBA8);
        if (presentations != disabledStart)
          throw new AssertionError("Dither applied while disabled");
        capture(client, "disabled.png");
        System.out.println("CBBG_CLASSIC_SMOKE_PASS presentations=" + presentations);
        phase = Phase.DONE;
        client.stop();
      }
    } catch (Exception | AssertionError failure) {
      throw new IllegalStateException("CBBG packaged smoke failed", failure);
    }
  }

  private static void createWorld(Minecraft client) {
    client
        .createWorldOpenFlows()
        .createFreshLevel(
            WORLD,
            new LevelSettings(
                "CBBG smoke",
                GameType.CREATIVE,
                false,
                Difficulty.PEACEFUL,
                true,
                new GameRules(),
                WorldDataConfiguration.DEFAULT),
            new WorldOptions(1234L, false, false),
            access ->
                access
                    .registryOrThrow(Registries.WORLD_PRESET)
                    .getHolderOrThrow(WorldPresets.FLAT)
                    .value()
                    .createWorldDimensions());
  }

  private static void setMode(Minecraft client, String mode) throws Exception {
    var commands = ClientCommandManager.getActiveDispatcher();
    var connection = client.getConnection();
    if (commands == null
        || connection == null
        || commands.execute(
                "cbbg mode set " + mode,
                (FabricClientCommandSource) connection.getSuggestionsProvider())
            != 1) {
      throw new AssertionError("Could not set CBBG mode through the client command: " + mode);
    }
  }

  private static void checkInstallation() throws Exception {
    var loader = FabricLoader.getInstance();
    var mod = loader.getModContainer("cbbg").orElseThrow();
    Path expected = Path.of(System.getProperty("cbbg.test.jar")).toRealPath();
    var paths = mod.getOrigin().getPaths();
    if (paths.size() != 1 || !Files.isSameFile(paths.get(0), expected)) {
      throw new AssertionError("The expected packaged JAR was not loaded: " + paths);
    }
    boolean sodium = loader.isModLoaded("sodium");
    if (sodium != Boolean.getBoolean("cbbg.test.sodium")) {
      throw new AssertionError("Unexpected Sodium presence");
    }
    if (loader.isModLoaded("iris") != Boolean.getBoolean("cbbg.test.iris")) {
      throw new AssertionError("Unexpected Iris presence");
    }
    System.out.println(
        "CBBG_CLASSIC_OPENGL "
            + GL11.glGetString(GL11.GL_RENDERER)
            + " / "
            + GL11.glGetString(GL11.GL_VERSION)
            + " sodium="
            + sodium);
  }

  static void requireFormat(Minecraft client, int expected) {
    int old = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    GlStateManager._bindTexture(client.getMainRenderTarget().getColorTextureId());
    try {
      int actual =
          GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
      if (actual != expected)
        throw new AssertionError("Texture format " + actual + ", expected " + expected);
    } finally {
      GlStateManager._bindTexture(old);
    }
  }

  private static void capture(Minecraft client, String name) throws Exception {
    try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
      image.writeToFile(Path.of(client.gameDirectory.getPath(), name));
    }
  }
}
