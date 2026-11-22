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
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NullMarked
@Mixin(DebugScreenEntries.class)
abstract class DebugScreenEntriesMixin {
  @Shadow @Final @Mutable
  public static Map<DebugScreenProfile, Map<ResourceKey<DebugScreenEntry>, DebugScreenEntryStatus>>
      PROFILES;

  @Inject(method = "bootstrap", at = @At("TAIL"))
  private static void cbbg$register(WritableRegistry<DebugScreenEntry> registry, CallbackInfo ci) {
    var key =
        ResourceKey.create(registry.key(), Identifier.fromNamespaceAndPath(Cbbg.MOD_ID, "cbbg"));
    Registry.register(registry, key, new CbbgDebugEntry());
    Map<DebugScreenProfile, Map<ResourceKey<DebugScreenEntry>, DebugScreenEntryStatus>> profiles =
        new EnumMap<>(DebugScreenProfile.class);
    for (var entry : PROFILES.entrySet()) {
      var status = new HashMap<>(entry.getValue());
      status.put(key, DebugScreenEntryStatus.IN_OVERLAY);
      profiles.put(entry.getKey(), Map.copyOf(status));
    }
    PROFILES = Map.copyOf(profiles);
  }
}
