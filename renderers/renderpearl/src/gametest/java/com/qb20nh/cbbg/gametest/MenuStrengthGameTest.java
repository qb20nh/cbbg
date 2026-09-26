package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.DitherPass;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Vector4f;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class MenuStrengthGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    CbbgConfig original = CbbgConfig.get();
    int originalBlur =
        context.computeOnClient(client -> client.options.getMenuBackgroundBlurriness());
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      context.runOnClient(client -> CbbgConfig.setMode(CbbgConfig.Mode.ENABLED));
      context.waitFor(client -> DitherController.isReady(), 600);
      Screen menu =
          context.computeOnClient(client -> new Screen(Component.literal("Menu strength test")) {});
      Screen inGameUi =
          context.computeOnClient(
              client ->
                  new Screen(Component.literal("In-game UI strength test")) {
                    @Override
                    public boolean isInGameUi() {
                      return true;
                    }
                  });
      check(context, menu, 5, 1, 2);
      check(context, menu, 0, 1, 1);
      check(context, inGameUi, 5, 1, 1);
      check(context, null, 5, 1, 1);
      check(context, menu, 10, 4, 4);
      context.runOnClient(client -> client.gui.setScreen(null));
    } finally {
      context.runOnClient(
          client -> {
            CbbgConfig.setMode(original.mode());
            CbbgConfig.setStrength(original.strength());
            client.options.menuBackgroundBlurriness().set(originalBlur);
          });
    }
  }

  private static void check(
      ClientGameTestContext context,
      @Nullable Screen screen,
      int blur,
      float base,
      float expectedStrength) {
    CompletableFuture<int[]> actual = new CompletableFuture<>();
    CompletableFuture<int[]> expected = new CompletableFuture<>();
    CompletableFuture<int[]> unboosted = new CompletableFuture<>();
    AtomicReference<@Nullable DitherPass> pass = new AtomicReference<>();
    try {
      context.runOnClient(
          client -> {
            client.gui.setScreen(screen);
            client.options.menuBackgroundBlurriness().set(blur);
            CbbgConfig.setStrength(base);
            var main = client.gameRenderer.mainRenderTarget();
            RenderSystem.getDevice()
                .createCommandEncoder()
                .clearColorTexture(
                    Objects.requireNonNull(main.getColorTexture()),
                    new Vector4f(127.25f / 255, 127.25f / 255, 127.25f / 255, 1));
            capture(main, actual); // Exercise the normal controller/screenshot route.
            DitherPass reference = new DitherPass();
            pass.set(reference);
            capture(
                reference.render(
                    Objects.requireNonNull(main.getColorTextureView()),
                    noiseView(),
                    expectedStrength,
                    1,
                    1,
                    false),
                expected);
            capture(
                reference.render(
                    Objects.requireNonNull(main.getColorTextureView()),
                    noiseView(),
                    base,
                    1,
                    1,
                    false),
                unboosted);
          });
      context.waitFor(client -> actual.isDone() && expected.isDone() && unboosted.isDone(), 200);
      if (!Arrays.equals(actual.join(), expected.join())) {
        throw new AssertionError("Menu strength mismatch: blur=" + blur + ", base=" + base);
      }
      if (base != expectedStrength && Arrays.equals(actual.join(), unboosted.join())) {
        throw new AssertionError("Fixture did not distinguish boosted and configured strength");
      }
    } finally {
      context.runOnClient(
          client -> {
            DitherPass current = pass.get();
            if (current != null) {
              current.close();
            }
          });
    }
  }

  private static GpuTextureView noiseView() {
    try {
      Field field = DitherController.class.getDeclaredField("noiseView");
      field.setAccessible(true);
      return (GpuTextureView) Objects.requireNonNull(field.get(null));
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Could not inspect the current noise frame", failure);
    }
  }

  private static void capture(RenderTarget target, CompletableFuture<int[]> result) {
    Screenshot.takeScreenshot(
        target,
        image -> {
          try (image) {
            result.complete(image.getPixels());
          } catch (Throwable failure) {
            result.completeExceptionally(failure);
          }
        });
  }
}
