package com.qb20nh.cbbg.mixin;

import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.render.CbbgShaders;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererReloadShadersMixin {

    private GameRendererReloadShadersMixin() {}

    @Inject(method = "reloadShaders", at = @At("TAIL"))
    private void cbbg$reloadShaders(ResourceProvider resourceProvider, CallbackInfo ci) {
        closeQuietly(CbbgShaders.getDither());
        closeQuietly(CbbgShaders.getDemo());
        CbbgShaders.clear();

        try {
            CbbgShaders.setDither(new ShaderInstance(resourceProvider, "cbbg_dither",
                    DefaultVertexFormat.BLIT_SCREEN));
            CbbgShaders.setDemo(new ShaderInstance(resourceProvider, "cbbg_demo",
                    DefaultVertexFormat.BLIT_SCREEN));
        } catch (Exception e) {
            Cbbg.LOGGER.warn("Failed to load cbbg shaders; dithering will be unavailable.", e);
            closeQuietly(CbbgShaders.getDither());
            closeQuietly(CbbgShaders.getDemo());
            CbbgShaders.clear();
        }
    }

    private static void closeQuietly(ShaderInstance shader) {
        if (shader == null) {
            return;
        }
        try {
            shader.close();
        } catch (Exception ignored) {
            // Ignore close failures during shader reload; vanilla will continue.
        }
    }
}
