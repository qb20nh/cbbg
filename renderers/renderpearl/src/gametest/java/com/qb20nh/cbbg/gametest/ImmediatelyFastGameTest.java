package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Exercise ImmediatelyFast's actual atlas ownership, not just mod presence. */
@NullMarked
public final class ImmediatelyFastGameTest implements FabricClientGameTest {
  private static final String ATLAS =
      "net.raphimc.immediatelyfast.feature.sign_text_buffering.SignAtlasRenderTarget";
  private static final int TEST_ATLAS_ID = Integer.MAX_VALUE;

  @Override
  // GPU ownership and recreation require checking the exact resource instances.
  @SuppressWarnings("ReferenceEquality")
  public void runTest(ClientGameTestContext context) {
    boolean expected =
        List.of(Objects.requireNonNull(System.getProperty("cbbg.test.compat", "none")).split("\\+"))
            .contains("immediatelyfast");
    if (FabricLoader.getInstance().isModLoaded("immediatelyfast") != expected) {
      throw new AssertionError("ImmediatelyFast presence differs from the requested fixture");
    }
    if (!expected) return;

    CbbgConfig original = CbbgConfig.get();
    List<RenderTarget> owned = new ArrayList<>();
    try {
      RenderTarget atlas = context.computeOnClient(client -> create(owned));
      var color = context.computeOnClient(client -> atlas.getColorTexture());
      var depth = context.computeOnClient(client -> atlas.getDepthTexture());
      for (var mode : CbbgConfig.Mode.values()) {
        for (var format :
            new CbbgConfig.PixelFormat[] {
              CbbgConfig.PixelFormat.RGBA16F, CbbgConfig.PixelFormat.RGBA32F
            }) {
          context.runOnClient(
              client -> {
                CbbgConfig.setMode(mode);
                CbbgConfig.setPixelFormat(format);
              });
          context.waitTicks(3);
          context.runOnClient(
              client -> {
                if (atlas.getColorTexture() != color
                    || atlas.getDepthTexture() != depth
                    || Objects.requireNonNull(color).isClosed()
                    || Objects.requireNonNull(depth).isClosed()) {
                  throw new AssertionError("CBBG replaced or closed ImmediatelyFast's atlas");
                }
                checkAtlas(client, atlas);
              });
          checkClearReadback(context, atlas);
        }
      }
      if (FabricLoader.getInstance().isModLoaded("iris")) {
        checkShaderTransitions(context, atlas);
      }
      context.runOnClient(
          client -> {
            close(atlas);
            owned.remove(atlas);
            if (!Objects.requireNonNull(color).isClosed()
                || !Objects.requireNonNull(depth).isClosed()) {
              throw new AssertionError("ImmediatelyFast atlas did not release its attachments");
            }
          });
      RenderTarget recreated = context.computeOnClient(client -> create(owned));
      context.runOnClient(
          client -> {
            if (recreated.getColorTexture() == color || recreated.getDepthTexture() == depth) {
              throw new AssertionError("Recreated atlas retained destroyed attachments");
            }
            checkAtlas(client, recreated);
          });
      checkClearReadback(context, recreated);
    } finally {
      context.runOnClient(
          client -> {
            for (RenderTarget target : owned) close(target);
            CbbgConfig.setMode(original.mode());
            CbbgConfig.setPixelFormat(original.pixelFormat());
          });
    }
  }

  // GPU ownership and recreation require checking the exact resource instances.
  @SuppressWarnings("ReferenceEquality")
  private static void checkShaderTransitions(ClientGameTestContext context, RenderTarget atlas) {
    IrisFixture.installPack();
    var color = context.computeOnClient(client -> atlas.getColorTexture());
    var depth = context.computeOnClient(client -> atlas.getDepthTexture());
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      context.runOnClient(client -> CbbgConfig.setMode(CbbgConfig.Mode.ENABLED));
      for (boolean active : new boolean[] {true, false}) {
        context.runOnClient(
            client -> {
              Object config = Objects.requireNonNull(IrisFixture.iris("getIrisConfig"));
              IrisFixture.invoke(config, "setShaderPackName", String.class, "cbbg-parity");
              IrisFixture.invoke(config, "setShadersEnabled", boolean.class, active);
              IrisFixture.saveAndReload();
            });
        context.waitFor(client -> IrisCompat.isShaderPackActive() == active, 600);
        context.runOnClient(
            client -> {
              if (atlas.getColorTexture() != color
                  || atlas.getDepthTexture() != depth
                  || Objects.requireNonNull(color).isClosed()
                  || Objects.requireNonNull(depth).isClosed()) {
                throw new AssertionError("Shader transition invalidated ImmediatelyFast's atlas");
              }
              if (active && (Boolean) Objects.requireNonNull(IrisFixture.iris("isFallback"))) {
                throw new AssertionError("Iris fell back during the ImmediatelyFast fixture");
              }
              checkAtlas(client, atlas);
            });
        checkClearReadback(context, atlas);
      }
    } finally {
      context.runOnClient(
          client -> {
            IrisFixture.invoke(
                Objects.requireNonNull(IrisFixture.iris("getIrisConfig")),
                "setShadersEnabled",
                boolean.class,
                false);
            IrisFixture.saveAndReload();
          });
    }
  }

  private static RenderTarget create(List<RenderTarget> owned) {
    try {
      RenderTarget atlas =
          Class.forName(ATLAS)
              .asSubclass(RenderTarget.class)
              .getConstructor(int.class)
              .newInstance(TEST_ATLAS_ID);
      owned.add(atlas);
      return atlas;
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot create the pinned ImmediatelyFast atlas", failure);
    }
  }

  // GPU ownership and recreation require checking the exact resource instances.
  @SuppressWarnings("ReferenceEquality")
  private static void checkAtlas(Minecraft client, RenderTarget atlas) {
    try {
      if (Objects.requireNonNull(atlas.getColorTexture()).getFormat() != GpuFormat.RGBA8_UNORM
          || Objects.requireNonNull(atlas.getDepthTexture()).getFormat() != GpuFormat.D32_FLOAT) {
        throw new AssertionError("CBBG changed ImmediatelyFast's attachment formats");
      }
      var type = atlas.getClass();
      Identifier id = (Identifier) type.getMethod("getTextureId").invoke(atlas);
      var registered = client.getTextureManager().getTexture(Objects.requireNonNull(id));
      if (registered.getTexture() != atlas.getColorTexture()
          || registered.getTextureView() != atlas.getColorTextureView()) {
        throw new AssertionError("ImmediatelyFast texture registration retained stale attachments");
      }
      type.getMethod("clear").invoke(atlas);
      var allocate = type.getMethod("findSlot", int.class, int.class);
      Object first = allocate.invoke(atlas, 8, 8);
      Object second = allocate.invoke(atlas, 8, 8);
      if (first == null || second == null || first == second) {
        throw new AssertionError("ImmediatelyFast atlas slot allocation failed");
      }
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Cannot exercise the pinned ImmediatelyFast atlas", failure);
    }
  }

  private static void checkClearReadback(ClientGameTestContext context, RenderTarget atlas) {
    CompletableFuture<@Nullable Void> complete = new CompletableFuture<>();
    context.runOnClient(
        client ->
            Screenshot.takeScreenshot(
                atlas,
                Math.max(1, atlas.width / 16),
                image -> {
                  try (image) {
                    // Vanilla screenshots force opaque alpha, even for a transparent clear.
                    for (int y = 0; y < image.getHeight(); y++) {
                      for (int x = 0; x < image.getWidth(); x++) {
                        if (image.getPixel(x, y) != 0xFF000000) {
                          throw new AssertionError(
                              "ImmediatelyFast atlas clear/readback was altered at " + x + "," + y);
                        }
                      }
                    }
                    complete.complete(null);
                  } catch (Throwable failure) {
                    complete.completeExceptionally(failure);
                  }
                }));
    context.waitFor(client -> complete.isDone(), 200);
    complete.join();
  }

  private static void close(RenderTarget atlas) {
    try {
      ((AutoCloseable) atlas).close();
    } catch (Exception failure) {
      throw new AssertionError("Cannot close ImmediatelyFast's atlas", failure);
    }
  }
}
