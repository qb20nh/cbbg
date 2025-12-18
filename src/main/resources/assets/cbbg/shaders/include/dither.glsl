// Includes blue-noise dithering logic

// STBN tile. Sample in pixel space for stable spatial alignment.
// Returns a dithered 8-bit quantized color.
vec3 cbbg_applyDither(vec3 color, sampler2D noiseSampler, float strength, vec2 noiseSize) {
    vec2 noiseUv = fract(gl_FragCoord.xy / noiseSize);
    vec3 n = texture(noiseSampler, noiseUv).rgb; // [0,1)

    vec3 x = color * 255.0 + (n - 0.5) * strength;
    vec3 q = floor(x + 0.5); // round to nearest 8-bit value
    return clamp(q, 0.0, 255.0) / 255.0;
}
