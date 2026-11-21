package com.qb20nh.cbbg.parity;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
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
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Tests the packaged mod through its public behavior and the live OpenGL pipeline. */
public final class ClassicPackagedSmoke implements ClientModInitializer {
  private static final String WORLD = "cbbg-smoke";

  private enum Phase {
    TITLE,
    OPENING_WORLD,
    REFERENCE,
    DITHERED,
    DISABLED,
    RESTARTED_DISABLED,
    STACK,
    IRIS,
    SATIN,
    RENDER_SCALE,
    DONE
  }

  private static long presentations;
  private final boolean restart = Boolean.getBoolean("cbbg.test.restart");
  private final boolean reference = Boolean.getBoolean("cbbg.test.reference");
  private final boolean iris = Boolean.getBoolean("cbbg.test.iris");
  private final boolean satin = Boolean.getBoolean("cbbg.test.satin");
  private final boolean renderScale = Boolean.getBoolean("cbbg.test.renderScale");
  private final boolean stack = Boolean.getBoolean("cbbg.test.stack");
  private ClassicStackSmoke stackSmoke;
  private ClassicIrisSmoke irisSmoke;
  private ClassicSatinSmoke satinSmoke;
  private ClassicRenderScaleSmoke renderScaleSmoke;
  private int ticks;
  private Phase phase = Phase.TITLE;
  private long enabledStart;
  private long disabledStart;
  private int disabledSince;

  @Override
  public void onInitializeClient() {
    if (Boolean.getBoolean("cbbg.test.vulkanMod")) {
      ClientTickEvents.END_CLIENT_TICK.register(new ClassicVulkanSmoke()::tick);
    } else {
      ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }
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
        checkInstallation(client);
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
        if (reference) {
          phase = Phase.REFERENCE;
        } else if (stack && !restart) {
          stackSmoke = new ClassicStackSmoke(client);
          phase = Phase.STACK;
        } else if (iris) {
          irisSmoke = new ClassicIrisSmoke(restart, ticks, presentations);
          phase = Phase.IRIS;
        } else if (satin) {
          satinSmoke = new ClassicSatinSmoke(client);
          phase = Phase.SATIN;
        } else if (renderScale) {
          renderScaleSmoke = new ClassicRenderScaleSmoke(client, ticks);
          phase = Phase.RENDER_SCALE;
        } else {
          phase = restart ? Phase.RESTARTED_DISABLED : Phase.DITHERED;
        }
      } else if (phase == Phase.REFERENCE && ticks >= disabledSince + 6) {
        requireFormat(client, GL11.GL_RGBA8);
        if (presentations != 0) throw new AssertionError("CBBG drew in the reference game");
        capture(client, "reference.png");
        System.out.println("CBBG_CLASSIC_REFERENCE_PASS");
        phase = Phase.DONE;
        client.stop();
      } else if (phase == Phase.STACK) {
        if (stackSmoke.tick(client)) {
          satinSmoke = new ClassicSatinSmoke(client);
          phase = Phase.SATIN;
        }
      } else if (phase == Phase.IRIS) {
        if (irisSmoke.tick(client, ticks, presentations)) {
          phase = Phase.DONE;
          client.stop();
        }
      } else if (phase == Phase.SATIN) {
        if (satinSmoke.tick(client)) {
          if (stack) {
            renderScaleSmoke = new ClassicRenderScaleSmoke(client, ticks);
            phase = Phase.RENDER_SCALE;
          } else {
            phase = Phase.DONE;
            client.stop();
          }
        }
      } else if (phase == Phase.RENDER_SCALE) {
        if (renderScaleSmoke.tick(client, ticks)) {
          if (stack && iris) {
            setMode(client, "demo");
            // The preceding command matrix fills the chat overlay over the shader oracle.
            client.gui.getChat().clearMessages(false);
            irisSmoke = new ClassicIrisSmoke(false, ticks, presentations);
            phase = Phase.IRIS;
          } else {
            phase = Phase.DONE;
            client.stop();
          }
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

  static void createWorld(Minecraft client) {
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
    command(client, "cbbg mode set " + mode);
  }

  static void command(Minecraft client, String command) throws Exception {
    var commands = ClientCommandManager.getActiveDispatcher();
    var connection = client.getConnection();
    if (commands == null
        || connection == null
        || commands.execute(
                command, (FabricClientCommandSource) connection.getSuggestionsProvider())
            != 1) {
      throw new AssertionError("CBBG client command failed: " + command);
    }
  }

  private void checkInstallation(Minecraft client) throws Exception {
    var loader = FabricLoader.getInstance();
    if (reference) {
      if (loader.isModLoaded("cbbg"))
        throw new AssertionError("CBBG was loaded in the reference game");
    } else {
      var mod = loader.getModContainer("cbbg").orElseThrow();
      Path expected = Path.of(System.getProperty("cbbg.test.jar")).toRealPath();
      var paths = mod.getOrigin().getPaths();
      if (paths.size() != 1 || !Files.isSameFile(paths.get(0), expected)) {
        throw new AssertionError("The expected packaged JAR was not loaded: " + paths);
      }
    }
    boolean sodium = loader.isModLoaded("sodium");
    if (sodium != Boolean.getBoolean("cbbg.test.sodium")) {
      throw new AssertionError("Unexpected Sodium presence");
    }
    String compatProfile = System.getProperty("cbbg.test.compatProfile");
    if (compatProfile != null) {
      Set<String> expected = Set.of(compatProfile.split("\\+"));
      for (String id :
          new String[] {
            "iris", "satin", "renderscale", "modmenu", "immediatelyfast", "chatpatches"
          }) {
        requireMod(loader, id, expected.contains(id));
      }
    } else {
      requireMod(loader, "iris", iris);
      requireMod(loader, "satin", satin);
      requireMod(loader, "renderscale", renderScale);
    }
    if (Boolean.getBoolean("cbbg.test.stack")) {
      for (String modId :
          new String[] {
            "modmenu", "sodium", "satin", "immediatelyfast", "chatpatches", "renderscale"
          }) {
        if (!loader.isModLoaded(modId))
          throw new AssertionError("Missing compatibility mod: " + modId);
      }
    }
    System.out.println(
        "CBBG_CLASSIC_OPENGL "
            + GL11.glGetString(GL11.GL_RENDERER)
            + " / "
            + GL11.glGetString(GL11.GL_VERSION)
            + " sodium="
            + sodium);
    String changer = System.getProperty("cbbg.test.contextMod");
    if (changer != null) {
      if (!loader.isModLoaded(changer))
        throw new AssertionError("Missing OpenGL context mod: " + changer);
      reportContext(client, changer);
    }
  }

  private static void requireMod(FabricLoader loader, String id, boolean expected) {
    if (loader.isModLoaded(id) != expected) {
      throw new AssertionError("Unexpected " + id + " presence");
    }
  }

  private void reportContext(Minecraft client, String changer) {
    long window = client.getWindow().getWindow();
    int major = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_CONTEXT_VERSION_MAJOR);
    int minor = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_CONTEXT_VERSION_MINOR);
    int profile = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_OPENGL_PROFILE);
    var capabilities = GL.getCapabilities();
    System.out.println(
        "CBBG_CLASSIC_CONTEXT mod="
            + changer
            + " reference="
            + reference
            + " actual="
            + major
            + "."
            + minor
            + " profile="
            + profile
            + " gl30="
            + capabilities.OpenGL30
            + " gl32="
            + capabilities.OpenGL32
            + " glsl="
            + GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION));
  }

  static void requireFormat(Minecraft client, int expected) {
    requireFormat(client.getMainRenderTarget(), expected);
  }

  static void requireFormat(RenderTarget target, int expected) {
    int old = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    GlStateManager._bindTexture(target.getColorTextureId());
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
