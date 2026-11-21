#version 150

#moj_import <cbbg:dither.glsl>

uniform sampler2D DiffuseSampler;
uniform sampler2D NoiseSampler;
uniform float Strength;
uniform vec2 CoordScale;
uniform float Demo;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 color = texture(DiffuseSampler, texCoord);
    float centerX = floor(float(textureSize(DiffuseSampler, 0).x) * 0.5);
    float strength = Demo > 0.5 && gl_FragCoord.x < centerX ? 0.0 : Strength;
    color.rgb = cbbg_applyDither(clamp(color.rgb, 0.0, 1.0), NoiseSampler, strength,
            vec2(textureSize(NoiseSampler, 0)), CoordScale);
    if (Demo > 0.5 && gl_FragCoord.x >= centerX && gl_FragCoord.x < centerX + 1.0) {
        color.rgb = vec3(1.0) - color.rgb;
    }
    fragColor = vec4(color.rgb, color.a);
}
