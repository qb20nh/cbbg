#version 150

#moj_import <cbbg_dither.glsl>

uniform sampler2D InSampler;
uniform sampler2D NoiseSampler;
uniform float Strength;
uniform vec2 CoordScale;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);
    vec2 noiseSize = vec2(textureSize(NoiseSampler, 0));
    vec3 result = cbbg_applyDither(clamp(color.rgb, 0.0, 1.0), NoiseSampler,
        Strength, noiseSize, CoordScale);
    fragColor = vec4(result, color.a);
}
