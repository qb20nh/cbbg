package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.qb20nh.cbbg.render.LegacyScreenshot;
import java.nio.IntBuffer;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.util.ScreenshotUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ScreenshotUtils.class)
public abstract class ScreenshotMixin {
    @WrapOperation(
            method = "saveScreenshot(Ljava/io/File;Ljava/lang/String;IILnet/minecraft/client/gl/Framebuffer;)Lnet/minecraft/text/Text;",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lorg/lwjgl/opengl/GL11;glGetTexImage(IIIILjava/nio/IntBuffer;)V"))
    private static void cbbg$read(int target, int level, int format, int type, IntBuffer pixels,
            Operation<Void> original, @Local(argsOnly = true) Framebuffer source) {
        LegacyScreenshot.read(source, () -> original.call(target, level, format, type, pixels));
    }
}
