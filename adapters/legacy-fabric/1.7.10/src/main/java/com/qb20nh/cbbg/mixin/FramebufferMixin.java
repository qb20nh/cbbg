package com.qb20nh.cbbg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.qb20nh.cbbg.render.Gl2Framebuffers;
import com.qb20nh.cbbg.render.LegacyFramebuffers;
import java.nio.ByteBuffer;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Framebuffer.class)
public abstract class FramebufferMixin {
    @WrapOperation(method = "attachTexture", at = @At(value = "INVOKE", remap = false,
            target = "Lorg/lwjgl/opengl/GL11;glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V"))
    private void cbbg$color(int target, int level, int internalFormat, int width, int height,
            int border, int format, int type, ByteBuffer pixels, Operation<Void> original) {
        if (LegacyFramebuffers.owns((Framebuffer) (Object) this) && format == GL11.GL_RGBA) {
            Gl2Framebuffers.allocateColor(LegacyFramebuffers.requestedFormat(), internalFormat, selected ->
                    original.call(target, level, selected, width, height, border, format, type, pixels));
        } else {
            original.call(target, level, internalFormat, width, height, border, format, type, pixels);
        }
    }
}
