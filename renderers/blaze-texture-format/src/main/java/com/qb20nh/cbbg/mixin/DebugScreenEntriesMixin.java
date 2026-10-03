package com.qb20nh.cbbg.mixin;

import com.qb20nh.cbbg.debug.CbbgDebugEntry;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers the 26.1 entry in each default F3 profile. */
@NullMarked
@Mixin(DebugScreenEntries.class)
public abstract class DebugScreenEntriesMixin {
  @Shadow @Final @Mutable
  private static Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> PROFILES;

  @Invoker("register")
  @SuppressWarnings("UnusedVariable")
  private static Identifier cbbg$register(Identifier id, DebugScreenEntry entry) {
    throw new AssertionError("Mixin invoker was not applied");
  }

  @Inject(method = "<clinit>", at = @At("TAIL"))
  private static void cbbg$install(CallbackInfo info) {
    Identifier id = Identifier.fromNamespaceAndPath("cbbg", "cbbg");
    cbbg$register(id, new CbbgDebugEntry());
    Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> updated =
        new EnumMap<>(DebugScreenProfile.class);
    for (var profile : PROFILES.entrySet()) {
      Map<Identifier, DebugScreenEntryStatus> statuses = new HashMap<>(profile.getValue());
      statuses.put(id, DebugScreenEntryStatus.IN_OVERLAY);
      updated.put(profile.getKey(), Map.copyOf(statuses));
    }
    PROFILES = Map.copyOf(updated);
  }
}
