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
    ivec2 sceneSize = textureSize(DiffuseSampler, 0);
    int center = sceneSize.x / 2;
    float localStrength = demo > 0.5 && pixel.x < center ? 0.0 : strength;
    // Keep the shared noise grid anchored to the lower-left of the scene.
    vec2 noisePixel = vec2(gl_FragCoord.x, float(sceneSize.y) - gl_FragCoord.y);
    color.rgb = cbbg_applyDitherAt(clamp(color.rgb, 0.0, 1.0), NoiseSampler, localStrength,
            vec2(textureSize(NoiseSampler, 0)), vec2(1.0), noisePixel);
    if (demo > 0.5 && pixel.x == center) color.rgb = vec3(1.0) - color.rgb;
    fragColor = color;
}
