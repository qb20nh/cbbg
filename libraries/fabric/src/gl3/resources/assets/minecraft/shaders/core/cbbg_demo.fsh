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
    float centerX = floor(float(textureSize(InSampler, 0).x) * 0.5);
    float strength = gl_FragCoord.x >= centerX ? Strength : 0.0;
    vec2 noiseSize = vec2(textureSize(NoiseSampler, 0));
    vec3 result = cbbg_applyDither(clamp(color.rgb, 0.0, 1.0), NoiseSampler,
        strength, noiseSize, CoordScale);
    if (gl_FragCoord.x >= centerX && gl_FragCoord.x < centerX + 1.0) {
        result = vec3(1.0) - result;
    }
    fragColor = vec4(result, color.a);
}
