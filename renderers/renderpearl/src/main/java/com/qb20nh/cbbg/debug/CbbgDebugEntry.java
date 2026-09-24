package com.qb20nh.cbbg.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.iris.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public final class CbbgDebugEntry implements DebugScreenEntry {
    @Override
    public void display(@NonNull DebugScreenDisplayer displayer, @Nullable Level level,
            @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
        var client = Minecraft.getInstance();
        var main = client.gameRenderer.mainRenderTarget().getColorTexture();
        var lightmap = client.gameRenderer.levelLightmap();
        displayer.addLine("cbbg: mode=" + CbbgClient.getEffectiveMode()
                + " (user=" + CbbgConfig.get().mode() + ") iris="
                + (IrisCompat.isShaderPackActive() ? 1 : 0)
                + " dis=" + (DitherController.isDisabled() ? 1 : 0));
        displayer.addLine("cbbg: main=" + (main == null ? "?" : main.getFormat().name())
                + " lm=" + lightmap.texture().getFormat().name()
                + " backend=" + RenderSystem.getDevice().getDeviceInfo().backendName()
                + " stbn=" + DitherController.getCurrentStbnFrameIndex()
                + "/" + DitherController.getStbnFrames());
    }
}
