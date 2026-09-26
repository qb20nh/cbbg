package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Exercises actual shader compilation failure through a temporary, non-owning shader source. */
@NullMarked
public final class ShaderFailureGameTest implements FabricClientGameTest {
  @Override
  // Shader failure must preserve the caller-owned input view.
  @SuppressWarnings("ReferenceEquality")
  public void runTest(ClientGameTestContext context) {
    CbbgConfig original = CbbgConfig.get();
    context.waitFor(client -> DitherController.isReady() && client.gui.overlay() == null, 600);
    try {
      context.runOnClient(
          client -> {
            var device = RenderSystem.getDevice();
            var source =
                new TextureTarget("CBBG shader failure input", 8, 8, GpuFormat.RGBA32_FLOAT, null);
            device
                .createCommandEncoder()
                .clearColorTexture(
                    Objects.requireNonNull(source.getColorTexture()),
                    new Vector4f(0.25f, 0.5f, 0.75f, 1));
            try {
              var healthy =
                  DitherController.screenshot(Objects.requireNonNull(source.getColorTextureView()));
              if (healthy == null)
                throw new AssertionError("Healthy dither pipeline was unavailable");
              var oldOutput = healthy.getColorTexture();
              var oldNoise =
                  (GpuTexture) Objects.requireNonNull(field(DitherController.class, null, "noise"));
              var fallback =
                  (PipelineCache)
                      Objects.requireNonNull(
                          field(RenderSystem.class, null, "fallbackPipelineCache"));
              var current = (PipelineCache) field(RenderSystem.class, null, "currentPipelineCache");
              var borrowed =
                  (ShaderSource)
                      Objects.requireNonNull(
                          field(
                              PipelineCache.class,
                              current == null ? fallback : current,
                              "shaderSource"));
              AtomicInteger attempts = new AtomicInteger();
              ShaderSource invalid =
                  new ShaderSource() {
                    @Override
                    public @Nullable String getShader(Identifier id, ShaderType type) {
                      String shader = borrowed.getShader(id, type);
                      if (id.equals(Identifier.fromNamespaceAndPath("cbbg", "core/cbbg_dither"))) {
                        if (shader == null)
                          throw new AssertionError("Original dither shader is missing");
                        attempts.incrementAndGet();
                        return shader + "\n#error CBBG deliberate shader compilation failure\n";
                      }
                      return shader;
                    }

                    @Override
                    public @Nullable CachedIncludeSource getInclude(Identifier id) {
                      return borrowed.getInclude(id);
                    }

                    @Override
                    public void close() {} // The real cache retains ownership of its source.
                  };
              var broken = new PipelineCache(device, invalid);
              var previous = RenderSystem.setCurrentPipelineCache(broken);
              try {
                replaceFallback(broken);
                if (DitherController.screenshot(
                        Objects.requireNonNull(source.getColorTextureView()))
                    != null) {
                  throw new AssertionError("Broken shader did not trigger fallback");
                }
              } finally {
                RenderSystem.setCurrentPipelineCache(previous);
                replaceFallback(fallback);
                broken.close();
              }
              if (attempts.get() == 0
                  || !DitherController.isDisabled()
                  || DitherController.isReady()
                  || DitherController.getStbnFrames() != 0
                  || !oldNoise.isClosed()
                  || !Objects.requireNonNull(oldOutput).isClosed()
                  || Objects.requireNonNull(source.getColorTexture()).isClosed()
                  || !CbbgConfig.get().equals(original)) {
                throw new AssertionError("Shader failure lost state or leaked owned resources");
              }
              if (DitherController.present(Objects.requireNonNull(source.getColorTextureView()))
                  != source.getColorTextureView()) {
                throw new AssertionError("Failed effect did not preserve the caller's input");
              }
              var lines = new ArrayList<String>();
              var displayer =
                  (DebugScreenDisplayer)
                      Proxy.newProxyInstance(
                          DebugScreenDisplayer.class.getClassLoader(),
                          new Class<?>[] {DebugScreenDisplayer.class},
                          (proxy, method, args) -> {
                            if (!method.getName().equals("addLine"))
                              throw new AssertionError(method);
                            lines.add((String) Objects.requireNonNull(args)[0]);
                            return null;
                          });
              Objects.requireNonNull(
                      DebugScreenEntries.getEntry(Identifier.fromNamespaceAndPath("cbbg", "cbbg")))
                  .display(displayer, client.level, null, null);
              if (lines.stream().noneMatch(line -> line.contains("dis=1"))) {
                throw new AssertionError("Debug overlay concealed shader failure");
              }
              try {
                Path evidence =
                    Path.of(
                        Objects.requireNonNull(System.getProperty("cbbg.test.evidence")),
                        "shader-failure");
                Files.createDirectories(evidence);
                Files.writeString(
                    evidence.resolve("state.txt"),
                    "compilerAttempts=" + attempts.get() + "\n" + String.join("\n", lines) + "\n");
              } catch (java.io.IOException failure) {
                throw new AssertionError("Could not retain shader failure evidence", failure);
              }
            } finally {
              source.destroyBuffers();
            }
            CbbgConfig.setMode(CbbgConfig.Mode.DISABLED);
            DitherController.beginFrame();
            CbbgConfig.setMode(original.mode());
            DitherController.beginFrame();
          });
      context.waitFor(client -> DitherController.isReady() && !DitherController.isDisabled(), 600);
    } finally {
      context.runOnClient(
          client -> {
            CbbgConfig.setMode(original.mode());
            if (DitherController.isDisabled()) DitherController.resetAfterToggle();
          });
    }
  }

  private static @Nullable Object field(Class<?> owner, @Nullable Object instance, String name) {
    try {
      var field = owner.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(instance);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot inspect " + name, failure);
    }
  }

  private static void replaceFallback(PipelineCache cache) {
    try {
      // RenderSystem's public setter is deliberately restricted to initial startup.
      var field = RenderSystem.class.getDeclaredField("fallbackPipelineCache");
      field.setAccessible(true);
      field.set(null, cache);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot replace the test fallback cache", failure);
    }
  }
}
