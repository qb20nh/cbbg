package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.GpuOutOfMemoryException;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.GlFormatOverride;
import com.qb20nh.cbbg.render.MainTargetFormatSupport;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MainTarget.class)
public abstract class MainTargetMixin {

    @Redirect(method = "allocateColorAttachment", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"))
    private GpuTexture cbbg$allocateColorAttachment(GpuDevice device, Supplier<String> label,
            int usage, @NonNull TextureFormat format, int width, int height, int depthOrLayers,
            int mipLevels) {
        if (!CbbgClient.isEnabled()) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        }

        CbbgConfig.PixelFormat requested = CbbgConfig.get().pixelFormat();
        CbbgConfig.PixelFormat effective = MainTargetFormatSupport.getEffective(requested);
        if (effective == CbbgConfig.PixelFormat.RGBA8) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        }

        GpuTexture texture = null;
        GpuOutOfMemoryException oom = null;
        Exception failure = null;

        GlFormatOverride.pushMainTargetColor();
        try {
            texture = device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        } catch (GpuOutOfMemoryException e) {
            oom = e;
        } catch (Exception e) {
            failure = e;
        } finally {
            GlFormatOverride.popMainTargetColor();
        }

        if (oom == null && failure == null) {
            return texture;
        }

        MainTargetFormatSupport.disable(effective, oom != null ? oom : failure);

        // Retry once with the next-best effective format (e.g. 32F -> 16F), otherwise fall back to
        // vanilla RGBA8.
        CbbgConfig.PixelFormat fallback = MainTargetFormatSupport.getEffective(requested);
        if (fallback == CbbgConfig.PixelFormat.RGBA8) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        }

        GlFormatOverride.pushMainTargetColor();
        try {
            return device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        } catch (GpuOutOfMemoryException e) {
            MainTargetFormatSupport.disable(fallback, e);
            throw e;
        } catch (Exception e) {
            MainTargetFormatSupport.disable(fallback, e);
            return device.createTexture(label, usage, format, width, height, depthOrLayers,
                    mipLevels);
        } finally {
            GlFormatOverride.popMainTargetColor();
        }
    }
}
