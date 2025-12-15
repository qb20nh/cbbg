#version 330

uniform sampler2D InSampler;
uniform sampler2D NoiseSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);

    // 128x128 STBN tile. Sample in pixel space for stable spatial alignment.
    vec2 noiseUv = fract(gl_FragCoord.xy / 128.0);
    vec3 n = texture(NoiseSampler, noiseUv).rgb; // [0,1)

    // 1-LSB blue-noise dither: minimal grain, still breaks up contour lines.
    vec3 c = clamp(color.rgb, 0.0, 1.0);
    float strength = 1.0; // LSBs

    vec3 x = c * 255.0 + (n - 0.5) * strength;
    vec3 q = floor(x + 0.5); // round to nearest 8-bit value
    color.rgb = clamp(q, 0.0, 255.0) / 255.0;

    fragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a);
}
