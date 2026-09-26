package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.serialization.JsonOps;
import com.qb20nh.cbbg.config.CbbgConfig;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.resources.Identifier;

/** Exercises the persistent-target branch used by resource-pack blur definitions. */
public final class PersistentBlurGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    CbbgConfig original = CbbgConfig.get();
    PostChain chain = context.computeOnClient(PersistentBlurGameTest::createChain);
    try {
      for (String format :
          new String[] {"RGBA16_FLOAT", "RGBA32_FLOAT", "RGBA16_FLOAT", "RGBA8_UNORM"}) {
        context.runOnClient(
            client -> {
              CbbgConfig.setMode(
                  format.equals("RGBA8_UNORM")
                      ? CbbgConfig.Mode.DISABLED
                      : CbbgConfig.Mode.ENABLED);
              CbbgConfig.setPixelFormat(
                  format.equals("RGBA32_FLOAT")
                      ? CbbgConfig.PixelFormat.RGBA32F
                      : CbbgConfig.PixelFormat.RGBA16F);
            });
        context.waitTicks(3);
        context.runOnClient(
            client -> {
              Map<Identifier, RenderTarget> targets = field(chain, "persistentTargets");
              RenderTarget previous = targets.get(Identifier.withDefaultNamespace("swap"));
              RenderTarget main = client.gameRenderer.mainRenderTarget();
              chain.process(main, GraphicsResourceAllocator.UNPOOLED);
              RenderTarget current = targets.get(Identifier.withDefaultNamespace("swap"));
              if (current == null
                  || !current.getColorTexture().getFormat().name().equals(format)
                  || current.width != main.width
                  || current.height != main.height) {
                throw new AssertionError(
                    "Persistent blur target has the wrong format or dimensions");
              }
              if (previous != null && (current == previous || previous.getColorTexture() != null)) {
                throw new AssertionError("Precision change retained the old persistent target");
              }
              chain.process(main, GraphicsResourceAllocator.UNPOOLED);
              if (targets.get(Identifier.withDefaultNamespace("swap")) != current) {
                throw new AssertionError("Unchanged blur settings discarded the persistent target");
              }
            });
      }
    } finally {
      context.runOnClient(
          client -> {
            Map<Identifier, RenderTarget> targets = field(chain, "persistentTargets");
            RenderTarget last = targets.get(Identifier.withDefaultNamespace("swap"));
            chain.close();
            CbbgConfig.setMode(original.mode());
            CbbgConfig.setPixelFormat(original.pixelFormat());
            if (!targets.isEmpty() || (last != null && last.getColorTexture() != null)) {
              throw new AssertionError("Closing blur leaked its persistent target");
            }
          });
    }
  }

  private static PostChain createChain(Minecraft client) {
    Identifier id = Identifier.withDefaultNamespace("blur");
    PostChain vanilla = client.getShaderManager().getPostChain(id, LevelTargetBundle.MAIN_TARGETS);
    try (var reader =
        client
            .getResourceManager()
            .getResourceOrThrow(Identifier.withDefaultNamespace("post_effect/blur.json"))
            .openAsReader()) {
      var json = JsonParser.parseReader(reader).getAsJsonObject();
      json.getAsJsonObject("targets").getAsJsonObject("swap").addProperty("persistent", true);
      PostChainConfig config = PostChainConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
      return PostChain.load(
          config,
          client.getTextureManager(),
          LevelTargetBundle.MAIN_TARGETS,
          id,
          (Projection) field(vanilla, "projection"),
          (ProjectionMatrixBuffer) field(vanilla, "projectionMatrixBuffer"));
    } catch (Exception failure) {
      throw new AssertionError("Could not load persistent blur fixture", failure);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T field(Object object, String name) {
    try {
      var field = object.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return (T) field.get(object);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not inspect " + name, failure);
    }
  }
}
