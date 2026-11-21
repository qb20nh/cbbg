#version 450

// DITHER_MATH

layout(binding = 0) uniform sampler2D DiffuseSampler;
layout(binding = 1) uniform sampler2D NoiseSampler;
layout(binding = 2) uniform Settings {
    float strength;
    float demo;
};
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 color = texelFetch(DiffuseSampler, pixel, 0);
    int center = textureSize(DiffuseSampler, 0).x / 2;
    float localStrength = demo > 0.5 && pixel.x < center ? 0.0 : strength;
    color.rgb = cbbg_applyDither(clamp(color.rgb, 0.0, 1.0), NoiseSampler, localStrength,
            vec2(textureSize(NoiseSampler, 0)), vec2(1.0));
    if (demo > 0.5 && pixel.x == center) color.rgb = vec3(1.0) - color.rgb;
    fragColor = color;
}
