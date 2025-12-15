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
    bool enabledSide = gl_FragCoord.x < width * 0.5;
    vec3 q = enabledSide ? q1 : q0;

    color.rgb = clamp(q, 0.0, 255.0) / 255.0;
    fragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a);
}


