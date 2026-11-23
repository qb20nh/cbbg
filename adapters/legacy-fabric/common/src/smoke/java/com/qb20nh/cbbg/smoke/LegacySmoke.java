package com.qb20nh.cbbg.smoke;

import com.google.gson.Gson;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import com.qb20nh.cbbg.render.LegacyFramebuffers;
import com.qb20nh.cbbg.render.LegacyDither;
import com.qb20nh.cbbg.render.LegacyTextureState;
import com.qb20nh.cbbg.render.Gl2DitherProgram;
import com.qb20nh.cbbg.smoke.mixin.ShaderEffectAccessor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.ShaderEffect;
import net.minecraft.client.sound.SoundCategory;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

public final class LegacySmoke {
    private static int stage;
    private static int checks;
    private static int pixelChecks;
    private static int languageChecks;
    private static int notificationChecks;
    private static int commandChecks;
    private static int settingsChecks;
    private static int menuChecks;
    private static int screenshotChecks;
    private static boolean finished;
    private static ShaderEffect effect;
    private static Framebuffer intermediate;
    private static int noiseTexture;
    private static int noiseDeletionCount;
    private static final Map<Integer, Integer> textureDeletions = new LinkedHashMap<>();
    private static long presentations;
    private static boolean failShader;
    private static int shaderFailures;

    public static void textureDeleted(int texture) {
        if (GL11.glIsTexture(texture)) throw new AssertionError("Deleted noise texture remains live: " + texture);
        textureDeletions.put(texture, textureDeletions.getOrDefault(texture, 0) + 1);
    }

    public static CharSequence shaderSource(CharSequence source) {
        if (failShader && source.toString().contains("gl_FragColor")) {
            shaderFailures++;
            return "#version 120\nintentional shader compilation failure";
        }
        return source;
    }

    public static void initialize() {
        CbbgConfig.setMode(CbbgConfig.Mode.ENABLED);
        CbbgConfig.setPixelFormat(PixelFormat.RGBA16F);
        CbbgConfig.setStbnSize(16);
        CbbgConfig.setStbnDepth(8);
        CbbgConfig.setStbnSeed(0);
    }

    public static void frame() {
        if (finished) return;
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            if (stage != 6 && LegacyDither.hasFailed()) throw new AssertionError("Legacy renderer failed");
            if (stage != 2 && stage != 8 && !LegacyDither.isReady()) return;
            Framebuffer main = client.getFramebuffer();
            require(LegacyFramebuffers.owns(main), "Main target is not tracked");
            require(main.depthAttachment >= 0, "Main depth attachment missing");
            if (stage == 0) {
                // Graphics parity does not exercise the legacy asynchronous audio decoder.
                client.options.setSoundVolume(SoundCategory.MASTER, 0f);
                client.getSoundManager().updateSoundVolume(SoundCategory.MASTER, 0f);
                require(LegacyNotificationParity.started == 0 && LegacyNotificationParity.completed == 0,
                        "Startup noise preparation emitted regeneration notifications");
                notificationChecks = LegacyNotificationParity.run();
                languageChecks = LegacyLanguageParity.run();
                commandChecks = LegacyCommandParity.run();
                settingsChecks = LegacySettingsParity.run();
                menuChecks = LegacyMenuParity.run();
                require(LegacyDither.presentations() > 0, "No dither presentation occurred");
                checkPresentationState(main.textureWidth);
                pixelChecks = LegacyPresentationParity.run();
                screenshotChecks = LegacyScreenshotParity.run();
                checkPrecisionBindings(main);
                noiseTexture = LegacyDither.textures().texture(0);
                noiseDeletionCount = textureDeletions.getOrDefault(noiseTexture, 0);
                require(GL11.glIsTexture(noiseTexture), "Noise texture missing");
                require(LegacyDither.textures().size() == 16 && LegacyDither.textures().depth() == 8,
                        "Wrong noise dimensions");
                require(format(main) == GL30.GL_RGBA16F, "Initial main target is not 16F");
                effect = new ShaderEffect(client.getTextureManager(), client.getResourceManager(), main,
                        new Identifier("cbbg", "shaders/post/smoke.json"));
                intermediate = ((ShaderEffectAccessor) effect).cbbg$getTarget("cbbg-test");
                require(LegacyFramebuffers.owns(intermediate), "Post-process target is not tracked");
                require(format(intermediate) == GL30.GL_RGBA16F, "Post-process target is not 16F");
                effect.setupDimensions(16, 12);
                require(intermediate.textureWidth == 16 && intermediate.textureHeight == 12,
                        "Post-process resize failed");
                require(format(intermediate) == GL30.GL_RGBA16F, "Post-process resize lost 16F");
                Framebuffer custom = new Framebuffer(8, 8, true);
                try {
                    require(!LegacyFramebuffers.owns(custom), "Custom target claimed");
                    require(format(custom) == GL11.GL_RGBA8, "Custom target format changed");
                    custom.resize(16, 12);
                    require(format(custom) == GL11.GL_RGBA8, "Custom resize changed format");
                    require(custom.textureWidth == 16 && custom.textureHeight == 12, "Custom resize failed");
                    require(custom.depthAttachment >= 0, "Custom depth attachment missing");
                } finally {
                    custom.delete();
                }
                CbbgConfig.setStbnSize(32);
            } else if (stage == 1) {
                presentations = LegacyDither.presentations();
                require(LegacyDither.textures().texture(0) == noiseTexture && LegacyDither.textures().size() == 16,
                        "Unconfirmed noise settings applied");
                CbbgConfig.setStbnSize(16);
                require(format(main) == GL30.GL_RGBA16F, "Enabled target did not retain RGBA16F");
                require(format(intermediate) == GL30.GL_RGBA16F, "Enabled post-process target did not retain RGBA16F");
                CbbgConfig.setMode(CbbgConfig.Mode.DISABLED);
            } else if (stage == 2) {
                require(LegacyDither.presentations() == presentations, "Disabled mode still presented dithering");
                require(!LegacyDither.isReady() && LegacyDither.textures() == null, "Disabled noise retained");
                require(textureDeletions.getOrDefault(noiseTexture, 0) > noiseDeletionCount,
                        "Disabled noise texture leaked");
                require(format(main) == GL11.GL_RGBA8, "Disabled target is not RGBA8");
                require(format(intermediate) == GL11.GL_RGBA8, "Disabled post-process target is not RGBA8");
                client.reloadResources();
                require(!LegacyDither.isReady() && LegacyDither.textures() == null,
                        "Resource reload activated disabled noise");
                CbbgConfig.setPixelFormat(PixelFormat.RGBA16F);
                CbbgConfig.setMode(CbbgConfig.Mode.DEMO);
            } else if (stage == 3) {
                require(LegacyDither.presentations() > presentations, "Demo mode did not resume presentation");
                require(format(main) == GL30.GL_RGBA16F, "Demo target is not 16F");
                require(format(intermediate) == GL30.GL_RGBA16F, "Demo post-process target is not 16F");
                noiseTexture = LegacyDither.textures().texture(0);
                noiseDeletionCount = textureDeletions.getOrDefault(noiseTexture, 0);
                CbbgConfig.setStbnSize(32);
                CommandRoutingParity.send("/cbbg stbn generate");
            } else if (stage == 4) {
                require(LegacyNotificationParity.started == 1 && LegacyNotificationParity.completed == 1,
                        "Regeneration did not emit exactly one start/completion pair");
                require(LegacyDither.textures().size() == 32, "Explicit reload did not apply noise settings");
                require(textureDeletions.getOrDefault(noiseTexture, 0) > noiseDeletionCount,
                        "Reload retained old noise texture");
                int[] oldProgram = new int[1];
                LegacyDither.present(main.textureWidth,
                        () -> oldProgram[0] = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
                require(oldProgram[0] != 0, "Missing shader before resource reload");
                noiseTexture = LegacyDither.textures().texture(0);
                CbbgConfig.setStbnSize(64);
                client.reloadResources();
                require(!GL20.glIsProgram(oldProgram[0]), "Resource reload retained old shader");
                require(LegacyDither.isReady() && LegacyDither.textures().texture(0) == noiseTexture,
                        "Resource reload replaced procedural noise");
                require(LegacyDither.textures().size() == 32, "Resource reload applied unconfirmed settings");
                CbbgConfig.setStbnSize(32);
                presentations = LegacyDither.presentations();
            } else if (stage == 5) {
                require(LegacyDither.presentations() > presentations,
                        "Presentation did not resume after resource reload");
                pixelChecks += LegacyPresentationParity.run();
                failShader = true;
                client.reloadResources();
                presentations = LegacyDither.presentations();
            } else if (stage == 6) {
                require(LegacyDither.hasFailed(), "Injected shader failure was not observed");
                settingsChecks += LegacySettingsParity.failedRenderer();
                pixelChecks += LegacyPresentationParity.runFallback();
                pixelChecks += LegacyPresentationParity.runFallback();
                require(shaderFailures == 1, "Failed shader retried without resource reload");
                require(LegacyDither.presentations() == presentations, "Failed shader counted a presentation");
                failShader = false;
                client.reloadResources();
            } else if (stage == 7) {
                require(LegacyDither.presentations() > presentations,
                        "Resource reload did not recover from shader failure");
                pixelChecks += LegacyPresentationParity.run();
                CbbgConfig.setMode(CbbgConfig.Mode.DISABLED);
                LegacyDither.regenerate();
            } else {
                if (LegacyNotificationParity.completed < 2) return;
                require(LegacyNotificationParity.started == 2 && LegacyNotificationParity.completed == 2,
                        "Disabled regeneration notification lifecycle is incorrect");
                require(!LegacyDither.isReady() && LegacyDither.textures() == null,
                        "Disabled regeneration uploaded GPU textures");
                finish(client, null);
            }
            stage++;
        } catch (Throwable failure) {
            finish(client, failure);
        }
    }

    private static int format(Framebuffer target) {
        int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.colorAttachment);
            return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
        }
    }

    private static void checkPrecisionBindings(Framebuffer main) {
        CbbgConfig.Mode mode = CbbgConfig.get().mode();
        Framebuffer read = new Framebuffer(7, 5, true);
        Framebuffer draw = new Framebuffer(9, 7, true);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read.fbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw.fbo);
            LegacyTextureState.bind(read.colorAttachment);
            GL11.glViewport(2, 3, 7, 9);
            CbbgConfig.setMode(CbbgConfig.Mode.DISABLED);
            LegacyFramebuffers.tick();
            require(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) == read.fbo,
                    "Precision transition changed custom read binding");
            require(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == draw.fbo,
                    "Precision transition changed custom draw binding");
            require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == read.colorAttachment,
                    "Precision transition changed custom texture binding");
            java.nio.IntBuffer viewport = org.lwjgl.BufferUtils.createIntBuffer(16);
            GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
            require(viewport.get(0) == 2 && viewport.get(1) == 3 && viewport.get(2) == 7 && viewport.get(3) == 9,
                    "Precision transition changed viewport");
        } finally {
            read.delete();
            draw.delete();
            CbbgConfig.setMode(mode);
            LegacyFramebuffers.tick();
            main.bind(true);
        }
    }

    private static void checkPresentationState(int width) {
        int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        LegacyTextureState.active(GL13.GL_TEXTURE0);
        int source = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        LegacyTextureState.active(GL13.GL_TEXTURE1);
        int noise = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int markerSource = GL11.glGenTextures();
        int markerNoise = GL11.glGenTextures();
        try (Gl2DitherProgram marker = new Gl2DitherProgram()) {
            marker.bind(0f, 1, 1, 1f, width, false);
            final int markerProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            LegacyTextureState.active(GL13.GL_TEXTURE0);
            LegacyTextureState.bind(markerSource);
            LegacyTextureState.active(GL13.GL_TEXTURE1);
            LegacyTextureState.bind(markerNoise);
            LegacyTextureState.active(GL13.GL_TEXTURE2);
            RuntimeException expected = new RuntimeException("Injected draw failure");
            try {
                LegacyDither.present(width, () -> {
                    require(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != markerProgram,
                            "Presentation did not bind its shader");
                    throw expected;
                });
                throw new AssertionError("Draw failure was swallowed");
            } catch (RuntimeException failure) {
                require(failure == expected, "Unexpected presentation failure");
            }
            require(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) == markerProgram, "Previous program not restored");
            require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE2, "Active texture unit not restored");
            LegacyTextureState.active(GL13.GL_TEXTURE0);
            require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == markerSource, "Source binding not restored");
            LegacyTextureState.active(GL13.GL_TEXTURE1);
            require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == markerNoise, "Noise binding not restored");
        } finally {
            GL20.glUseProgram(program);
            LegacyTextureState.active(GL13.GL_TEXTURE0);
            LegacyTextureState.bind(source);
            LegacyTextureState.active(GL13.GL_TEXTURE1);
            LegacyTextureState.bind(noise);
            LegacyTextureState.active(unit);
            GL11.glDeleteTextures(markerSource);
            GL11.glDeleteTextures(markerNoise);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void finish(MinecraftClient client, Throwable failure) {
        finished = true;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("passed", failure == null);
        report.put("stage", stage);
        report.put("checks", checks);
        report.put("pixelChecks", pixelChecks);
        report.put("languageChecks", languageChecks);
        report.put("notificationChecks", notificationChecks);
        report.put("commandChecks", commandChecks);
        report.put("settingsChecks", settingsChecks);
        report.put("menuChecks", menuChecks);
        report.put("screenshotChecks", screenshotChecks);
        report.put("regenerationsCompleted", LegacyNotificationParity.completed);
        report.put("textureDeletions", textureDeletions.values().stream().mapToInt(Integer::intValue).sum());
        report.put("presentations", LegacyDither.presentations());
        report.put("injectedShaderFailures", shaderFailures);
        report.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
        report.put("glVersion", GL11.glGetString(GL11.GL_VERSION));
        report.put("javaVersion", System.getProperty("java.version"));
        if (failure != null) report.put("failure", failure.toString());
        try {
            Path path = Paths.get(System.getProperty("cbbg.test.report"));
            Files.createDirectories(path.getParent());
            Files.write(path, new Gson().toJson(report).getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new RuntimeException("Cannot write smoke report", error);
        } finally {
            if (effect != null) effect.disable();
            client.scheduleStop();
        }
    }
}
