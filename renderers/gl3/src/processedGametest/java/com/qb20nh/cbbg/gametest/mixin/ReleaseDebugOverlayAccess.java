package com.qb20nh.cbbg.gametest.mixin;

import java.util.List;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DebugScreenOverlay.class)
public interface ReleaseDebugOverlayAccess {
  @Invoker("getGameInformation")
  List<String> cbbg$information();
}
