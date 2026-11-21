#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
uniform sampler2D NoiseSampler;
layout(std140) uniform CbbgDitherInfo {
    float Strength;
    float NoiseInvSize;
    vec2 CoordScale;
    vec2 WindowSize;
};
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    vec4 color = textureLod(InSampler, vec2(texCoord.x, 1.0 - texCoord.y), 0.0);
    float strength = Strength;
#ifdef CBBG_DEMO
    float centerX = floor(WindowSize.x * 0.5);
    strength = gl_FragCoord.x >= centerX ? Strength : 0.0;
#endif
    vec2 pixel = vec2(gl_FragCoord.x, WindowSize.y - gl_FragCoord.y);
    vec2 noiseUv = (floor(pixel * CoordScale) + vec2(0.5)) * NoiseInvSize;
    vec3 noise = textureLod(NoiseSampler, noiseUv, 0.0).rgb;
    color.rgb = clamp(floor(clamp(color.rgb, 0.0, 1.0) * 255.0 + (noise - 0.5) * strength + 0.5), 0.0, 255.0) / 255.0;
#ifdef CBBG_DEMO
    if (gl_FragCoord.x >= centerX && gl_FragCoord.x < centerX + 1.0) color.rgb = vec3(1.0) - color.rgb;
#endif
    fragColor = vec4(color.rgb, color.a);
}
