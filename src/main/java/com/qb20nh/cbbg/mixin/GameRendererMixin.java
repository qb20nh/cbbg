package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import com.qb20nh.cbbg.render.MenuBlurGuard;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    private static final ThreadLocal<Boolean> DID_PUSH = ThreadLocal.withInitial(() -> false);

    @Shadow
    @Final
    private CrossFrameResourcePool resourcePool;

    @Inject(method = "processBlurEffect", at = @At("HEAD"))
    private void cbbg$processBlurEffect$begin(CallbackInfo ci) {
        final CbbgConfig.PixelFormat desired = desiredMenuBlurFormat();

        if (MenuBlurGuard.updateLastBlurFormat(desired)) {
            // --- ImmediatelyFast compat: do not remove ---
            // Rationale: Minecraft caches post-chain internal targets (including menu blur targets)
            // in this CrossFrameResourcePool. cbbg upgrades only the blur chain's internal targets
            // to float formats. Clearing here ensures cached RGBA8 (or float) targets are not reused
            // across cbbg toggles / pixel-format changes.
            this.resourcePool.clear();
        }

        // Only enable the guard when cbbg is active and a float format is actually in use.
        if (!CbbgClient.isEnabled() || desired == CbbgConfig.PixelFormat.RGBA8) {
            DID_PUSH.set(false);
            return;
        }

        MenuBlurGuard.push(desired);
        DID_PUSH.set(true);
    }

    @Inject(method = "processBlurEffect", at = @At("RETURN"))
    private void cbbg$processBlurEffect$end(CallbackInfo ci) {
        try {
            if (Boolean.TRUE.equals(DID_PUSH.get())) {
                MenuBlurGuard.pop();
            }
        } finally {
            DID_PUSH.remove();
        }
    }

    private static CbbgConfig.PixelFormat desiredMenuBlurFormat() {
        if (!CbbgClient.isEnabled()) {
            return CbbgConfig.PixelFormat.RGBA8;
        }
        return MainTargetFormatSupport.getEffective(CbbgConfig.get().pixelFormat());
    }
}


