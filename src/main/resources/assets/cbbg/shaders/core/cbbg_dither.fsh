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

    // 1-LSB blue-noise dither: minimal grain, still breaks up contour lines.
    vec2 stbnSize = vec2(textureSize(NoiseSampler, 0));
    color.rgb = cbbg_applyDither(c, NoiseSampler, Strength, stbnSize, CoordScale);

    fragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a);
}
