package com.qb20nh.cbbg.gametest;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.serialization.JsonOps;
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

/** Checks persistent targets in a resource-pack blur definition. */
public final class ReleasePersistentBlurGameTest implements FabricClientGameTest {
  private static final Identifier SWAP = Identifier.withDefaultNamespace("swap");

  @Override
  public void runTest(ClientGameTestContext context) {
    var original = ReleaseClient.settings();
    try (var world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      PostChain chain = context.computeOnClient(ReleasePersistentBlurGameTest::createChain);
      try {
        for (GpuFormat format :
            new GpuFormat[] {
              GpuFormat.RGBA16_FLOAT,
              GpuFormat.RGBA32_FLOAT,
              GpuFormat.RGBA16_FLOAT,
              GpuFormat.RGBA8_UNORM
            }) {
          ReleaseClient.command(
              context, "mode set " + (format == GpuFormat.RGBA8_UNORM ? "disabled" : "enabled"));
          ReleaseClient.command(
              context, "format set " + (format == GpuFormat.RGBA32_FLOAT ? "rgba32f" : "rgba16f"));
          ReleaseClient.awaitFormat(context, format);
          context.runOnClient(
              client -> {
                Map<Identifier, RenderTarget> targets = field(chain, "persistentTargets");
                RenderTarget previous = targets.get(SWAP);
                RenderTarget main = client.gameRenderer.mainRenderTarget();
                chain.process(main, GraphicsResourceAllocator.UNPOOLED);
                RenderTarget current = targets.get(SWAP);
                if (current == null
                    || current.getColorTexture().getFormat() != format
                    || current.width != main.width
                    || current.height != main.height) {
                  throw new AssertionError(
                      "Persistent blur target has the wrong format or dimensions");
                }
                if (previous != null
                    && (current == previous || previous.getColorTexture() != null)) {
                  throw new AssertionError("Precision change retained the old persistent target");
                }
                chain.process(main, GraphicsResourceAllocator.UNPOOLED);
                if (targets.get(SWAP) != current) {
                  throw new AssertionError("Unchanged settings replaced the persistent target");
                }
              });
        }
      } finally {
        try {
          context.runOnClient(
              client -> {
                Map<Identifier, RenderTarget> targets = field(chain, "persistentTargets");
                RenderTarget last = targets.get(SWAP);
                chain.close();
                if (!targets.isEmpty() || (last != null && last.getColorTexture() != null)) {
                  throw new AssertionError("Closing blur retained its persistent target");
                }
              });
        } finally {
          ReleaseClient.command(context, "format set " + original.get("pixelFormat").getAsString());
          ReleaseClient.command(context, "mode set " + original.get("mode").getAsString());
        }
      }
    }
  }

  private static PostChain createChain(Minecraft client) {
    Identifier id = Identifier.withDefaultNamespace("blur");
    PostChain vanilla = client.getShaderManager().getPostChain(id, LevelTargetBundle.MAIN_TARGETS);
    if (vanilla == null) throw new AssertionError("Vanilla blur chain is unavailable");
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
      throw new AssertionError("Could not inspect Minecraft field " + name, failure);
    }
  }
}
