#version 330

#moj_import <cbbg:dither.glsl>

uniform sampler2D InSampler;
uniform sampler2D NoiseSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);
    vec3 c = clamp(color.rgb, 0.0, 1.0);

    float width = float(textureSize(InSampler, 0).x);
    float centerX = floor(width * 0.5);
    // Left = vanilla (no dither), right = cbbg (dither)
    bool enabledSide = gl_FragCoord.x >= centerX;

    // "Disabled" side: strength 0.0 (just quantize). "Enabled" side: strength 1.0.
    float strength = enabledSide ? 1.0 : 0.0;
    
    color.rgb = cbbg_applyDither(c, NoiseSampler, strength);

    // 1px vertical separator at the split.
    if (gl_FragCoord.x >= centerX && gl_FragCoord.x < centerX + 1.0) {
        color.rgb = vec3(1.0) - color.rgb; // high-contrast line against any background
    }
    fragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a);
}


