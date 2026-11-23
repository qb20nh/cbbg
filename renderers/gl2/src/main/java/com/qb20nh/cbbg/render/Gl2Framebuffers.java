package com.qb20nh.cbbg.render;

import com.qb20nh.cbbg.config.CbbgConfig.PixelFormat;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.function.IntConsumer;
import java.util.logging.Logger;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/** LWJGL 2 color storage; callers retain ownership of game framebuffer objects. */
public final class Gl2Framebuffers {
    private static final Logger LOGGER = Logger.getLogger("cbbg");
    private static final EnumMap<PixelFormat, Boolean> SUPPORTED = new EnumMap<>(PixelFormat.class);
    private static ContextCapabilities context;

    private Gl2Framebuffers() {}

    /** Call on the render thread with the destination texture bound; RGBA8 preserves vanilla storage. */
    public static void allocateColor(PixelFormat requested, int originalFormat, IntConsumer allocate) {
        ContextCapabilities capabilities = GLContext.getCapabilities();
        if (context != capabilities) {
            context = capabilities;
            SUPPORTED.clear();
        }
        while (true) {
            PixelFormat format = FormatPolicy.effective(requested,
                    candidate -> SUPPORTED.computeIfAbsent(candidate, value -> probe(value, capabilities)));
            if (format == PixelFormat.RGBA8) {
                allocate.accept(originalFormat);
                return;
            }
            int internalFormat = glFormat(format);
            allocate.accept(internalFormat);
            int error = GL11.glGetError();
            int stored = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if (error == GL11.GL_NO_ERROR && stored == internalFormat) return;
            SUPPORTED.put(format, false);
            LOGGER.warning("Legacy framebuffer allocation failed for " + format
                    + " (GL " + error + ", stored format " + stored + "); falling back");
        }
    }

    private static int glFormat(PixelFormat format) {
        return format == PixelFormat.RGBA32F ? GL30.GL_RGBA32F : GL30.GL_RGBA16F;
    }

    private static boolean probe(PixelFormat format, ContextCapabilities caps) {
        if (!(caps.OpenGL30 || caps.GL_ARB_texture_float)
                || !(caps.OpenGL30 || caps.GL_ARB_framebuffer_object || caps.GL_EXT_framebuffer_object)) {
            return false;
        }
        boolean splitBindings = caps.OpenGL30 || caps.GL_ARB_framebuffer_object || caps.GL_EXT_framebuffer_blit;
        int textureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int drawBinding = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readBinding = splitBindings ? GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) : drawBinding;
        int texture = GL11.glGenTextures();
        int framebuffer = create(caps);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, glFormat(format), 4, 4, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            bind(caps, GL30.GL_FRAMEBUFFER, framebuffer);
            int status;
            if (caps.OpenGL30) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, texture, 0);
                status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            } else if (caps.GL_ARB_framebuffer_object) {
                ARBFramebufferObject.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, texture, 0);
                status = ARBFramebufferObject.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            } else {
                EXTFramebufferObject.glFramebufferTexture2DEXT(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, texture, 0);
                status = EXTFramebufferObject.glCheckFramebufferStatusEXT(GL30.GL_FRAMEBUFFER);
            }
            int error = GL11.glGetError();
            return status == GL30.GL_FRAMEBUFFER_COMPLETE && error == GL11.GL_NO_ERROR
                    && GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT) == glFormat(format);
        } finally {
            if (splitBindings) {
                bind(caps, GL30.GL_READ_FRAMEBUFFER, readBinding);
                bind(caps, GL30.GL_DRAW_FRAMEBUFFER, drawBinding);
            } else {
                bind(caps, GL30.GL_FRAMEBUFFER, drawBinding);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureBinding);
            if (caps.OpenGL30) GL30.glDeleteFramebuffers(framebuffer);
            else if (caps.GL_ARB_framebuffer_object) ARBFramebufferObject.glDeleteFramebuffers(framebuffer);
            else EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer);
            GL11.glDeleteTextures(texture);
        }
    }

    static int create(ContextCapabilities caps) {
        if (caps.OpenGL30) return GL30.glGenFramebuffers();
        if (caps.GL_ARB_framebuffer_object) return ARBFramebufferObject.glGenFramebuffers();
        return EXTFramebufferObject.glGenFramebuffersEXT();
    }

    static void bind(ContextCapabilities caps, int target, int framebuffer) {
        if (caps.OpenGL30) GL30.glBindFramebuffer(target, framebuffer);
        else if (caps.GL_ARB_framebuffer_object) ARBFramebufferObject.glBindFramebuffer(target, framebuffer);
        else EXTFramebufferObject.glBindFramebufferEXT(target, framebuffer);
    }

    static void attach(ContextCapabilities caps, int texture) {
        if (caps.OpenGL30) GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        else if (caps.GL_ARB_framebuffer_object) ARBFramebufferObject.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        else EXTFramebufferObject.glFramebufferTexture2DEXT(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
    }

    static int status(ContextCapabilities caps) {
        if (caps.OpenGL30) return GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (caps.GL_ARB_framebuffer_object) return ARBFramebufferObject.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        return EXTFramebufferObject.glCheckFramebufferStatusEXT(GL30.GL_FRAMEBUFFER);
    }

    static void delete(ContextCapabilities caps, int framebuffer) {
        if (caps.OpenGL30) GL30.glDeleteFramebuffers(framebuffer);
        else if (caps.GL_ARB_framebuffer_object) ARBFramebufferObject.glDeleteFramebuffers(framebuffer);
        else EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer);
    }
}
