package com.qb20nh.cbbg.mixin;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.debug.CbbgDebugEntry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenEntries.class)
public abstract class DebugScreenEntriesMixin {

    private DebugScreenEntriesMixin() {}

    @Shadow
    @Final
    @Mutable
    private static Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> PROFILES;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void cbbg$register(CallbackInfo ci) {
        Identifier id = Identifier.fromNamespaceAndPath(CbbgClient.MOD_ID, "cbbg");
        DebugScreenEntries.register(id, new CbbgDebugEntry());

        Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> updated =
                new EnumMap<>(DebugScreenProfile.class);
        updated.putAll(PROFILES);
        for (Map.Entry<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> e : PROFILES
                .entrySet()) {
            Map<Identifier, DebugScreenEntryStatus> status = new HashMap<>(e.getValue());
            status.put(id, DebugScreenEntryStatus.IN_OVERLAY);
            updated.put(e.getKey(), Map.copyOf(status));
        }
        PROFILES = Map.copyOf(updated);
    }
}
