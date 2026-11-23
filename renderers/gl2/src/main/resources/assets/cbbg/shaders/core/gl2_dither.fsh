// GLSL version and the canonical dither function are prepended by Gl2DitherProgram.
uniform sampler2D DiffuseSampler;
uniform sampler2D NoiseSampler;
uniform float Strength;
uniform vec2 NoiseSize;
uniform vec2 CoordScale;
uniform float SourceWidth;
uniform float Demo;

varying vec2 texCoord;

void main() {
    vec4 color = texture2D(DiffuseSampler, texCoord);
    float centerX = floor(SourceWidth * 0.5);
    float strength = Demo > 0.5 && gl_FragCoord.x < centerX ? 0.0 : Strength;
    color.rgb = cbbg_applyDither(clamp(color.rgb, 0.0, 1.0), NoiseSampler, strength,
            NoiseSize, CoordScale);
    if (Demo > 0.5 && gl_FragCoord.x >= centerX && gl_FragCoord.x < centerX + 1.0) {
        color.rgb = vec3(1.0) - color.rgb;
    }
    gl_FragColor = vec4(color.rgb, color.a);
}
