package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.qb20nh.cbbg.render.DitherController;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
  @WrapMethod(
      method =
          "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V")
  @SuppressWarnings(
      "ReferenceEquality") // Only the game's active main target receives screenshot dithering.
  private static void cbbg$screenshot(
      RenderTarget target, int scale, Consumer<NativeImage> result, Operation<Void> original) {
    var input = target.getColorTextureView();
    var rendered =
        target == Minecraft.getInstance().getMainRenderTarget() && input != null
            ? DitherController.screenshot(input)
            : null;
    original.call(rendered == null ? target : rendered, scale, result);
  }
}
