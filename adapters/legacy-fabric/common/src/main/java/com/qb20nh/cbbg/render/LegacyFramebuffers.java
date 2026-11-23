package com.qb20nh.cbbg.render;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.nio.ByteBuffer;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;

/** Game-object ownership stays in the adapter, outside the LWJGL 2 allocator. */
public final class LegacyFramebuffers {
    private static final Set<Framebuffer> TARGETS = Collections.newSetFromMap(new WeakHashMap<Framebuffer, Boolean>());
    private static PixelFormat previous;

    private LegacyFramebuffers() {}

    public static Framebuffer track(Framebuffer target) {
        TARGETS.add(target);
        refreshColor(target);
        return target;
    }

    public static boolean owns(Framebuffer target) {
        return TARGETS.contains(target);
    }

    public static PixelFormat requestedFormat() {
        CbbgConfig config = CbbgConfig.get();
        return config.mode() == CbbgConfig.Mode.DISABLED ? PixelFormat.RGBA8 : config.pixelFormat();
    }

    public static void tick() {
        PixelFormat requested = requestedFormat();
        if (previous == requested) return;
        previous = requested;
        for (Framebuffer target : TARGETS.toArray(new Framebuffer[0])) {
            refreshColor(target);
        }
    }

    private static void refreshColor(Framebuffer target) {
        if (target.fbo < 0 || target.colorAttachment < 0) return;
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            // Only color storage changes; framebuffer/depth objects and caller state stay intact.
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.colorAttachment);
            Gl2Framebuffers.allocateColor(requestedFormat(), GL11.GL_RGBA8,
                    format -> GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format,
                            target.textureWidth, target.textureHeight, 0,
                            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null));
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        }
    }
}
