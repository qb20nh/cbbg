package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.qb20nh.cbbg.render.NeutralVignette;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@NullMarked
@Mixin(Hud.class)
public abstract class HudVignetteMixin {
  @SuppressWarnings("UnusedMethod") // Invoked by the MixinExtras operation wrapper.
  @WrapOperation(
      method =
          "extractVignette(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/Entity;)V",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIII)V"),
      require = 1,
      allow = 1)
  // skipcq: JAVA-W0324
  private void cbbg$neutralVignette(
      GuiGraphicsExtractor graphics,
      RenderPipeline pipeline,
      Identifier texture,
      int x,
      int y,
      float u,
      float v,
      int width,
      int height,
      int textureWidth,
      int textureHeight,
      int color,
      Operation<Void> original) {
    if (!NeutralVignette.shouldSkip(pipeline, texture, color)) {
      original.call(
          graphics,
          pipeline,
          texture,
          x,
          y,
          u,
          v,
          width,
          height,
          textureWidth,
          textureHeight,
          color);
    }
  }
}
