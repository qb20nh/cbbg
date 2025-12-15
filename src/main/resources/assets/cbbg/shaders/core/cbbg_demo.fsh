#version 330

uniform sampler2D InSampler;
uniform sampler2D NoiseSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);
    vec3 c = clamp(color.rgb, 0.0, 1.0);

    // "Disabled" side: straight 8-bit quantize (no dithering).
    vec3 x0 = c * 255.0;
    vec3 q0 = floor(x0 + 0.5);

    // "Enabled" side: 1-LSB blue-noise dither, then quantize.
    vec2 noiseUv = fract(gl_FragCoord.xy / 128.0);
    vec3 n = texture(NoiseSampler, noiseUv).rgb; // [0,1)
    vec3 x1 = c * 255.0 + (n - 0.5) * 1.0;
    vec3 q1 = floor(x1 + 0.5);

    float width = float(textureSize(InSampler, 0).x);
    float centerX = floor(width * 0.5);
    // Left = vanilla (no dither), right = cbbg (dither)
    bool enabledSide = gl_FragCoord.x >= centerX;
    vec3 q = enabledSide ? q1 : q0;

    color.rgb = clamp(q, 0.0, 255.0) / 255.0;

    // 1px vertical separator at the split.
    if (gl_FragCoord.x >= centerX && gl_FragCoord.x < centerX + 1.0) {
        color.rgb = vec3(1.0) - color.rgb; // high-contrast line against any background
    }
    fragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a);
}


