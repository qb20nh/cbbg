package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.renderscale.RenderScaleCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import com.qb20nh.cbbg.render.MenuBlurGuard;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTarget.class)
public abstract class RenderTargetCreateBuffersMixin {
  @Inject(method = "createBuffers", at = @At("RETURN"))
  private void cbbg$format(int width, int height, boolean clearError, CallbackInfo info) {
    if (!CbbgClient.isEnabled()) return;
    var target = (RenderTarget) (Object) this;
    boolean main = target instanceof MainTarget;
    var scoped = MenuBlurGuard.getActiveFormat();
    if (!main && scoped == null && !cbbg$isRenderScaleTarget(width, height)) return;
    var requested = scoped != null ? scoped : CbbgConfig.get().pixelFormat();
    if (MainTargetFormatSupport.getEffective(requested) == CbbgConfig.PixelFormat.RGBA8) return;
    try {
      MainTargetFormatSupport.allocate(target, requested, main);
    } catch (RuntimeException failure) {
      target.destroyBuffers();
      throw failure;
    }
    // Reallocation discards vanilla's initial clear. Preserve its per-target alpha/depth values.
    target.clear(clearError);
    target.unbindRead();
  }

  @Unique
  private static boolean cbbg$isRenderScaleTarget(int width, int height) {
    if (!RenderScaleCompat.isLoaded()) return false;
    double scale = RenderScaleCompat.getDitherScale();
    if (scale >= 1) return false;
    var mc = Minecraft.getInstance();
    var window = mc.getWindow();
    int expectedWidth = Math.max(1, (int) Math.round(window.getWidth() * scale));
    int expectedHeight = Math.max(1, (int) Math.round(window.getHeight() * scale));
    var main = mc.getMainRenderTarget();
    return Math.abs(width - expectedWidth) <= 1
        && Math.abs(height - expectedHeight) <= 1
        && main != null
        && width < main.width
        && height < main.height;
  }
}
