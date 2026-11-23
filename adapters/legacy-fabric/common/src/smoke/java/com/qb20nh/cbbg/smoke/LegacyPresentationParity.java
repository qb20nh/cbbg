package com.qb20nh.cbbg.smoke;

import com.qb20nh.cbbg.render.LegacyDither;
import com.qb20nh.cbbg.render.LegacyTextureState;
import net.minecraft.client.gl.Framebuffer;

final class LegacyPresentationParity {
    private static final Gl2PresentationParity.Platform PLATFORM = new Gl2PresentationParity.Platform() {
        public void active(int unit) { LegacyTextureState.active(unit); }
        public void bind(int texture) { LegacyTextureState.bind(texture); }
        public Gl2PresentationParity.Target create(int width, int height) {
            final Framebuffer target = new Framebuffer(width, height, false);
            return new Gl2PresentationParity.Target() {
                public int texture() { return target.colorAttachment; }
                public void bind() { target.bind(true); }
                public void draw() { target.draw(width, height); }
                public void delete() { target.delete(); }
            };
        }
    };

    static int run() { return Gl2PresentationParity.run(LegacyDither.renderer(), PLATFORM, false); }
    static int runFallback() { return Gl2PresentationParity.run(LegacyDither.renderer(), PLATFORM, true); }
}
