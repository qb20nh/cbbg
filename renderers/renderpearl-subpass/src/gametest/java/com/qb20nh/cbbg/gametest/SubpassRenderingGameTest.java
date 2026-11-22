package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.gui.CbbgConfigScreen;
import com.qb20nh.cbbg.render.DitherController;
import java.lang.reflect.Modifier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.core.registries.BuiltInClientRegistries;
import net.minecraft.client.core.registries.ClientRegistries;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class SubpassRenderingGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    context.runOnClient(client -> checkDebugProfiles());
    var original = CbbgConfig.get();
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      context.runOnClient(
          client -> {
            String actual = RenderSystem.getDevice().getDeviceInfo().backendName();
            String expected = System.getProperty("cbbg.test.backend", "opengl");
            if (!actual.equalsIgnoreCase(expected)) {
              throw new AssertionError("Expected " + expected + " but got " + actual);
            }
            CbbgConfig.setPixelFormat(CbbgConfig.PixelFormat.RGBA16F);
            CbbgConfig.setMode(CbbgConfig.Mode.DEMO);
          });
      long initialFrames =
          context.computeOnClient(client -> DitherController.getPresentationCount());
      context.waitFor(
          client -> {
            var color = client.gameRenderer.mainRenderTarget().getColorTexture();
            return color != null
                && color.getFormat() == GpuFormat.RGBA16_FLOAT
                && DitherController.isReady()
                && DitherController.getPresentationCount() > initialFrames;
          },
          600);
      context.takeScreenshot("cbbg-subpass-demo");
      context.runOnClient(
          client -> client.gui.setScreen(new CbbgConfigScreen(client.gui.screen())));
      context.waitForScreen(CbbgConfigScreen.class);
      context.waitTicks(5);
      context.takeScreenshot("cbbg-subpass-settings");
      context.runOnClient(
          client -> {
            client.gui.setScreen(null);
            CbbgConfig.setMode(CbbgConfig.Mode.DISABLED);
          });
      context.waitFor(
          client -> {
            var color = client.gameRenderer.mainRenderTarget().getColorTexture();
            return color != null && color.getFormat() == GpuFormat.RGBA8_UNORM;
          });
    } finally {
      context.runOnClient(
          client -> {
            CbbgConfig.setMode(original.mode());
            CbbgConfig.setPixelFormat(original.pixelFormat());
          });
    }
  }

  private static void checkDebugProfiles() {
    var key =
        ResourceKey.create(
            ClientRegistries.DEBUG_SCREEN_ENTRY,
            Identifier.fromNamespaceAndPath(Cbbg.MOD_ID, "cbbg"));
    if (!BuiltInClientRegistries.DEBUG_SCREEN_ENTRY.containsKey(key)) {
      throw new AssertionError("CBBG debug entry is missing");
    }
    for (var profile : DebugScreenEntries.PROFILES.values()) {
      if (profile.get(key) != DebugScreenEntryStatus.IN_OVERLAY) {
        throw new AssertionError("CBBG is absent from a default debug profile");
      }
    }
    try {
      if (!Modifier.isFinal(DebugScreenEntries.class.getField("PROFILES").getModifiers())) {
        throw new AssertionError("The debug profile field must remain final");
      }
    } catch (NoSuchFieldException failure) {
      throw new AssertionError("The debug profile field is missing", failure);
    }
  }
}
