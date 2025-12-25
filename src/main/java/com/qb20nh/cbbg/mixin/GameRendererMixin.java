package com.qb20nh.cbbg.mixin;

import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import com.qb20nh.cbbg.render.MenuBlurGuard;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    private static final ThreadLocal<Boolean> DID_PUSH = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> DID_PUSH_RESIZE =
            ThreadLocal.withInitial(() -> false);

    @Shadow
    @Final
    private ResourceManager resourceManager;

    @Invoker("loadBlurEffect")
    protected abstract void cbbg$invokeLoadBlurEffect(ResourceProvider resourceProvider);

    @Inject(method = "processBlurEffect", at = @At("HEAD"))
    private void cbbg$processBlurEffect$begin(float partialTick, CallbackInfo ci) {
        final CbbgConfig.PixelFormat desired = desiredMenuBlurFormat();

        if (MenuBlurGuard.updateLastBlurFormat(desired)) {
            // Recreate the blur effect so its internal targets are reallocated with the desired
            // format (RGBA8 vs float) after cbbg toggles or pixel-format changes.
            this.cbbg$invokeLoadBlurEffect(this.resourceManager);
        }
    }

    @Inject(method = "loadBlurEffect", at = @At("HEAD"))
    private void cbbg$loadBlurEffect$begin(ResourceProvider resourceProvider, CallbackInfo ci) {
        final CbbgConfig.PixelFormat desired = desiredMenuBlurFormat();

        // Only enable the guard when cbbg is active and a float format is actually in use.
        if (!CbbgClient.isEnabled() || desired == CbbgConfig.PixelFormat.RGBA8) {
            DID_PUSH.set(false);
            return;
        }

        MenuBlurGuard.push(desired);
        DID_PUSH.set(true);
    }

    @Inject(method = "loadBlurEffect", at = @At("RETURN"))
    private void cbbg$loadBlurEffect$end(ResourceProvider resourceProvider, CallbackInfo ci) {
        try {
            if (Boolean.TRUE.equals(DID_PUSH.get())) {
                MenuBlurGuard.pop();
            }
        } finally {
            DID_PUSH.remove();
        }
    }

    @Inject(method = "resize",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/PostChain;resize(II)V", ordinal = 1,
                    shift = At.Shift.BEFORE))
    private void cbbg$resize$beforeBlurResize(int width, int height, CallbackInfo ci) {
        final CbbgConfig.PixelFormat desired = desiredMenuBlurFormat();
        if (!CbbgClient.isEnabled() || desired == CbbgConfig.PixelFormat.RGBA8) {
            DID_PUSH_RESIZE.set(false);
            return;
        }
        MenuBlurGuard.push(desired);
        DID_PUSH_RESIZE.set(true);
    }

    @Inject(method = "resize",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/PostChain;resize(II)V", ordinal = 1,
                    shift = At.Shift.AFTER))
    private void cbbg$resize$afterBlurResize(int width, int height, CallbackInfo ci) {
        try {
            if (Boolean.TRUE.equals(DID_PUSH_RESIZE.get())) {
                MenuBlurGuard.pop();
            }
        } finally {
            DID_PUSH_RESIZE.remove();
        }
    }

    private static CbbgConfig.PixelFormat desiredMenuBlurFormat() {
        if (!CbbgClient.isEnabled()) {
            return CbbgConfig.PixelFormat.RGBA8;
        }
        return MainTargetFormatSupport.getEffective(CbbgConfig.get().pixelFormat());
    }
}
