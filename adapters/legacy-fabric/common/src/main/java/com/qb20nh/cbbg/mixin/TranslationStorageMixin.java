package com.qb20nh.cbbg.mixin;

import java.util.List;
import java.util.Map;
import net.minecraft.client.resource.language.TranslationStorage;
import net.minecraft.resource.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TranslationStorage.class)
public abstract class TranslationStorageMixin {
    @Shadow private Map<String, String> translations;

    // Vanilla .lang loading has no escape handling. Decode only our generated namespace,
    // once after all packs have loaded, so literal backslashes survive pack layering.
    @Inject(method = "load(Lnet/minecraft/resource/ResourceManager;Ljava/util/List;)V", at = @At("RETURN"))
    private void cbbg$decode(ResourceManager resources, List<String> languages, CallbackInfo ci) {
        translations.replaceAll((key, value) -> key.startsWith("cbbg.") ? decode(value) : value);
    }

    @Unique
    private static String decode(String value) {
        return com.qb20nh.cbbg.LegacyLanguage.decode(value);
    }
}
