package com.qb20nh.cbbg.mixin;

import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.debug.CbbgDebugState;
import com.qb20nh.cbbg.debug.CbbgGlNames;
import com.qb20nh.cbbg.render.CbbgDither;
import java.util.List;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {

    private DebugScreenOverlayMixin() {}

    @Inject(method = "getGameInformation", at = @At("RETURN"))
    private void cbbg$getGameInformation(CallbackInfoReturnable<List<String>> cir) {
        List<String> lines = cir.getReturnValue();
        if (lines == null) {
            return;
        }

        CbbgConfig.Mode user = CbbgClient.getUserMode();
        CbbgConfig.Mode effective = CbbgClient.getEffectiveMode();
        boolean iris = IrisCompat.isShaderPackActive();

        lines.add("cbbg: mode=" + effective + " (user=" + user + ") iris=" + (iris ? 1 : 0)
                + " dis=" + (CbbgDither.isDisabled() ? 1 : 0));

        lines.add("cbbg: main="
                + CbbgGlNames.glInternalShort(CbbgDebugState.getMainInternalFormat()) + " lm="
                + (CbbgDebugState.getLightmapInternalFormat() < 0 ? "?"
                        : CbbgGlNames.glInternalShort(CbbgDebugState.getLightmapInternalFormat()))
                + " fb=" + CbbgGlNames.glEncodingShort(CbbgDebugState.getDefaultFbEncoding())
                + " srgb=" + (CbbgDebugState.isFramebufferSrgb() ? 1 : 0) + " stbn="
                + CbbgDither.getCurrentStbnFrameIndex() + "/" + CbbgDither.getStbnFrames());
    }
}
