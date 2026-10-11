package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL20;

/** Exercises packaged shader reload, disposal, fallback pixels and recovery on GL3. */
@NullMarked
public final class ReleaseShaderFailureGameTest implements FabricClientGameTest {
  private static final String SHADERS = "com.qb20nh.cbbg.render.CbbgShaders";
  private static final String DITHER = "com.qb20nh.cbbg.render.CbbgDither";

  @Override
  public void runTest(ClientGameTestContext context) {
    ReleaseGraphics.check(context);
    try (var world = context.worldBuilder().create()) {
      ReleaseViewport.waitForChunks(world);
      context.waitFor(client -> render(client) != null, 600);
      context.runOnClient(
          client -> {
            ShaderInstance dither = Objects.requireNonNull(shader(false));
            ShaderInstance demo = Objects.requireNonNull(shader(true));
            int oldDither = dither.getId();
            int oldDemo = demo.getId();
            int[] partial = {0};
            boolean[] injected = {false};
            ResourceProvider resources = client.getResourceManager();
            ResourceProvider malformed =
                id -> {
                  if (id.getPath().equals("shaders/core/cbbg_demo.json")) {
                    partial[0] = Objects.requireNonNull(shader(false)).getId();
                    injected[0] = true;
                    throw new IllegalStateException("Injected CBBG demo shader resource failure");
                  }
                  return resources.getResource(id);
                };
            try {
              reload(malformed);
              if (!injected[0] || partial[0] <= 0)
                throw new AssertionError("Packaged shader failure was not injected");
              if (shader(false) != null || shader(true) != null)
                throw new AssertionError("Shader reload retained a partial shader pair");
              if (GL20.glIsProgram(oldDither)
                  || GL20.glIsProgram(oldDemo)
                  || GL20.glIsProgram(partial[0]))
                throw new AssertionError("Failed reload leaked a shader program");
              long before = draws();
              if (render(client) != null || draws() != before)
                throw new AssertionError("Shader failure produced a successful dither draw");
              checkFallback(client);
            } finally {
              reload(resources);
            }
            if (shader(false) == null || shader(true) == null || render(client) == null)
              throw new AssertionError("Valid shader reload did not recover dithering");
          });
      long before = context.computeOnClient(client -> draws());
      context.waitFor(client -> draws() > before, 600);
      ReleasePixels.check(context, false);
      ReleaseScreenshots.capture(
          context,
          "shader-recovered",
          image -> {
            if (image.getWidth() <= 0 || image.getHeight() <= 0)
              throw new AssertionError("Recovered screenshot is empty");
          });
    }
  }

  private static void checkFallback(Minecraft client) {
    RenderTarget target = client.getMainRenderTarget();
    try (NativeImage expected =
            (NativeImage)
                Objects.requireNonNull(
                    invoke(
                        "com.qb20nh.cbbg.render.Rgba8Readback",
                        "com.mojang.blaze3d.platform.NativeImage capture(com.mojang.blaze3d.pipeline.RenderTarget)",
                        new Class<?>[] {RenderTarget.class},
                        new Object[] {target}));
        NativeImage actual = Screenshot.takeScreenshot(target)) {
      if (actual.getWidth() != expected.getWidth() || actual.getHeight() != expected.getHeight())
        throw new AssertionError("Fallback screenshot dimensions changed");
      for (int y = 0; y < actual.getHeight(); y++) {
        for (int x = 0; x < actual.getWidth(); x++) {
          if (actual.getPixelRGBA(x, y) != expected.getPixelRGBA(x, y))
            throw new AssertionError("Fallback screenshot pixels changed at " + x + "," + y);
        }
      }
      try {
        java.nio.file.Path evidence =
            java.nio.file.Path.of(
                Objects.requireNonNull(System.getProperty("cbbg.test.evidence")),
                "shader-fallback.png");
        java.nio.file.Files.createDirectories(Objects.requireNonNull(evidence.getParent()));
        actual.writeToFile(evidence);
      } catch (java.io.IOException failure) {
        throw new AssertionError("Cannot retain shader fallback pixels", failure);
      }
    }
  }

  private static @Nullable TextureTarget render(Minecraft client) {
    return (TextureTarget)
        invoke(
            DITHER,
            "com.mojang.blaze3d.pipeline.TextureTarget renderScreenshotTarget(com.mojang.blaze3d.pipeline.RenderTarget)",
            new Class<?>[] {RenderTarget.class},
            new Object[] {client.getMainRenderTarget()});
  }

  private static @Nullable ShaderInstance shader(boolean demo) {
    return (ShaderInstance)
        invoke(
            SHADERS,
            "net.minecraft.client.renderer.ShaderInstance get(boolean)",
            new Class<?>[] {boolean.class},
            new Object[] {demo});
  }

  private static void reload(ResourceProvider resources) {
    invoke(
        SHADERS,
        "void reload(net.minecraft.server.packs.resources.ResourceProvider)",
        new Class<?>[] {ResourceProvider.class},
        new Object[] {resources});
  }

  private static long draws() {
    return ReleaseStartupObservations.draws();
  }

  private static @Nullable Object invoke(
      String owner, String member, Class<?>[] types, Object[] args) {
    try {
      Class<?> type = Class.forName(ReleaseMapping.className(owner));
      return type.getMethod(ReleaseMapping.memberName(owner, member), types).invoke(null, args);
    } catch (InvocationTargetException failure) {
      throw new AssertionError("Packaged shader API threw", failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Packaged shader API changed", failure);
    }
  }
}
