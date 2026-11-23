package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.platform.GLX;
import com.qb20nh.cbbg.config.CbbgConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;

public final class LegacyScreenshot {
    private LegacyScreenshot() {}

    public static void read(Framebuffer source, Runnable read) {
        if (source != MinecraftClient.getInstance().getFramebuffer()
                || CbbgConfig.get().mode() == CbbgConfig.Mode.DISABLED || !GLX.supportsFbo()) {
            read.run();
            return;
        }
        Gl2Screenshot.read(source.colorAttachment, source.textureWidth, source.textureHeight,
                LegacyDither.renderer(), LegacyTextureState::active, LegacyTextureState::bind, read);
    }
}
