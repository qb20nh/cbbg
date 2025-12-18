package com.qb20nh.cbbg.debug;

import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.compat.IrisCompat;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.CbbgDither;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public final class CbbgDebugEntry implements DebugScreenEntry {
        @Override
        public void display(@NonNull DebugScreenDisplayer displayer,
                        @Nullable Level serverOrClientLevel, @Nullable LevelChunk clientChunk,
                        @Nullable LevelChunk serverChunk) {
                CbbgConfig.Mode user = CbbgClient.getUserMode();
                CbbgConfig.Mode effective = CbbgClient.getEffectiveMode();
                boolean iris = IrisCompat.isShaderPackActive();

                displayer.addLine("cbbg: mode=" + effective + " (user=" + user + ") iris="
                                + (iris ? 1 : 0) + " dis=" + (CbbgDither.isDisabled() ? 1 : 0));

                displayer.addLine("cbbg: main="
                                + CbbgGlNames.glInternalShort(
                                                CbbgDebugState.getMainInternalFormat())
                                + " lm="
                                + (CbbgDebugState.getLightmapInternalFormat() < 0 ? "?"
                                                : CbbgGlNames.glInternalShort(CbbgDebugState
                                                                .getLightmapInternalFormat()))
                                + " fb="
                                + CbbgGlNames.glEncodingShort(CbbgDebugState.getDefaultFbEncoding())
                                + " srgb=" + (CbbgDebugState.isFramebufferSrgb() ? 1 : 0) + " stbn="
                                + CbbgDither.getCurrentStbnFrameIndex() + "/"
                                + CbbgDither.getStbnFrames());
        }
}
