#version 330
#extension GL_ARB_separate_shader_objects : require

#include <cbbg:dither.glsl>

uniform sampler2D InSampler;
uniform sampler2D NoiseSampler;
layout(std140) uniform CbbgDitherInfo {
    float Strength;
    vec2 CoordScale;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);
    float strength = Strength;
#ifdef CBBG_DEMO
    float centerX = floor(float(textureSize(InSampler, 0).x) * 0.5);
    strength = gl_FragCoord.x >= centerX ? Strength : 0.0;
#endif
    color.rgb = cbbg_applyDither(clamp(color.rgb, 0.0, 1.0), NoiseSampler,
            strength, vec2(textureSize(NoiseSampler, 0)), CoordScale);
#ifdef CBBG_DEMO
    if (gl_FragCoord.x >= centerX && gl_FragCoord.x < centerX + 1.0) {
        color.rgb = vec3(1.0) - color.rgb;
    }
#endif
    fragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a);
}
