package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.qb20nh.cbbg.Cbbg;
import com.qb20nh.cbbg.debug.CbbgDebugEntry;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.core.registries.ClientRegistries;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@NullMarked
@Mixin(DebugScreenEntries.class)
abstract class DebugScreenEntriesMixin {
  @Inject(method = "bootstrap", at = @At("TAIL"))
  private static void cbbg$register(WritableRegistry<DebugScreenEntry> registry, CallbackInfo ci) {
    Registry.register(registry, cbbg$debugKey(), new CbbgDebugEntry());
  }

  @ModifyExpressionValue(
      method = "<clinit>",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Ljava/util/Map;of(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"),
      require = 1,
      allow = 1)
  private static Map<DebugScreenProfile, Map<ResourceKey<DebugScreenEntry>, DebugScreenEntryStatus>>
      cbbg$addToProfiles( // skipcq: JAVA-W0324
      Map<DebugScreenProfile, Map<ResourceKey<DebugScreenEntry>, DebugScreenEntryStatus>> vanilla) {
    var key = cbbg$debugKey();
    Map<DebugScreenProfile, Map<ResourceKey<DebugScreenEntry>, DebugScreenEntryStatus>> profiles =
        new EnumMap<>(DebugScreenProfile.class);
    for (var entry : vanilla.entrySet()) {
      var status = new HashMap<>(entry.getValue());
      status.put(key, DebugScreenEntryStatus.IN_OVERLAY);
      profiles.put(entry.getKey(), Map.copyOf(status));
    }
    return Map.copyOf(profiles);
  }

  @Unique
  private static ResourceKey<DebugScreenEntry> cbbg$debugKey() {
    return ResourceKey.create(
        ClientRegistries.DEBUG_SCREEN_ENTRY, Identifier.fromNamespaceAndPath(Cbbg.MOD_ID, "cbbg"));
  }
}
