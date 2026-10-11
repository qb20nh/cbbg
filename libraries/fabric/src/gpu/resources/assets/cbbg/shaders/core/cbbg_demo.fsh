#version 150

#moj_import <cbbg:dither.glsl>

uniform sampler2D InSampler;
uniform sampler2D NoiseSampler;

layout(std140) uniform CbbgDitherInfo {
    float Strength;
    vec2 CoordScale;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);
    vec3 c = clamp(color.rgb, 0.0, 1.0);

    float width = float(textureSize(InSampler, 0).x);
    float centerX = floor(width * 0.5);
    // Left = vanilla (no dither), right = cbbg (dither)
    bool enabledSide = gl_FragCoord.x >= centerX;

    // "Disabled" side: strength 0.0 (no dither). "Enabled" side: user strength.
    float strength = enabledSide ? Strength : 0.0;
    
    vec2 stbnSize = vec2(textureSize(NoiseSampler, 0));
    color.rgb = cbbg_applyDither(c, NoiseSampler, strength, stbnSize, CoordScale);

    // 1px vertical separator at the split.
    if (gl_FragCoord.x >= centerX && gl_FragCoord.x < centerX + 1.0) {
        color.rgb = vec3(1.0) - color.rgb; // high-contrast line against any background
    }
    fragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a);
}
