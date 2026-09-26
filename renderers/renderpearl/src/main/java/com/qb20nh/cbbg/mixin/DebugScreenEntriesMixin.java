package com.qb20nh.cbbg.mixin;

import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.debug.CbbgDebugEntry;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenEntries.class)
public abstract class DebugScreenEntriesMixin {
  @Shadow @Final @Mutable
  private static Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> PROFILES;

  @Shadow
  private static Identifier register(Identifier id, DebugScreenEntry entry) {
    throw new AssertionError();
  }

  @Inject(method = "<clinit>", at = @At("TAIL"))
  private static void cbbg$register(CallbackInfo ci) {
    Identifier id = Identifier.fromNamespaceAndPath(Cbbg.MOD_ID, "cbbg");
    register(id, new CbbgDebugEntry());
    Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> updated =
        new EnumMap<>(DebugScreenProfile.class);
    for (var entry : PROFILES.entrySet()) {
      Map<Identifier, DebugScreenEntryStatus> status = new HashMap<>(entry.getValue());
      status.put(id, DebugScreenEntryStatus.IN_OVERLAY);
      updated.put(entry.getKey(), Map.copyOf(status));
    }
    PROFILES = Map.copyOf(updated);
  }
}
