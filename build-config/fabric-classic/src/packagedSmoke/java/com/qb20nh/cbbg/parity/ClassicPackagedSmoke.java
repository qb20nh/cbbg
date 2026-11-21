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
  private static long presentations;
  private int ticks;
  private int stage;
  private long worldStart;
  private long disabledStart;

  @Override
  public void onInitializeClient() {
    ClientTickEvents.END_CLIENT_TICK.register(this::tick);
  }

  public static void observePresentation() {
    presentations++;
  }

  private void tick(Minecraft client) {
    try {
      if (++ticks > 1200) throw new AssertionError("Timed out at smoke stage " + stage);
      if (stage == 0 && client.getOverlay() == null && client.screen instanceof TitleScreen) {
        checkInstallation();
        client
            .createWorldOpenFlows()
            .createFreshLevel(
                "cbbg-smoke",
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
        stage = 1;
      } else if (stage == 1 && client.player != null && client.screen == null) {
        worldStart = presentations;
        stage = 2;
      } else if (stage == 2 && presentations >= worldStart + 10) {
        requireFormat(client, GL30.GL_RGBA16F);
        capture(client, "enabled.png");
        var commands = ClientCommandManager.getActiveDispatcher();
        var connection = client.getConnection();
        if (commands == null
            || connection == null
            || commands.execute(
                    "cbbg mode set disabled",
                    (FabricClientCommandSource) connection.getSuggestionsProvider())
                != 1) {
          throw new AssertionError("Could not turn CBBG off through the client command");
        }
        stage = 3;
      } else if (stage == 3) {
        requireFormat(client, GL11.GL_RGBA8);
        disabledStart = presentations;
        stage = 4;
      } else if (stage >= 4 && ++stage == 10) {
        if (presentations != disabledStart)
          throw new AssertionError("Dither applied while disabled");
        capture(client, "disabled.png");
        System.out.println("CBBG_CLASSIC_SMOKE_PASS presentations=" + presentations);
        client.stop();
      }
    } catch (Exception | AssertionError failure) {
      throw new IllegalStateException("CBBG packaged smoke failed", failure);
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
    System.out.println(
        "CBBG_CLASSIC_OPENGL "
            + GL11.glGetString(GL11.GL_RENDERER)
            + " / "
            + GL11.glGetString(GL11.GL_VERSION)
            + " sodium="
            + sodium);
  }

  private static void requireFormat(Minecraft client, int expected) {
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
