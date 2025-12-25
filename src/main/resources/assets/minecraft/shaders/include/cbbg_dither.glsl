// Blue-noise dithering logic (used by cbbg_dither and cbbg_demo)

// STBN tile. Sample in pixel space for stable spatial alignment.
// Returns a dithered 8-bit quantized color.
vec3 cbbg_applyDither(vec3 color, sampler2D noiseSampler, float strength, vec2 noiseSize,
        vec2 coordScale) {
    // When RenderScale renders the world at a lower resolution, we want the dithering to happen on
    // that same pixel grid so the dither pattern upscales cleanly (crisp pixels).
    // gl_FragCoord is pixel-centered (0.5, 1.5, ...). Convert to integer pixel indices before
    // scaling, then convert back to centered coords for stable noise sampling.
    vec2 pixelIndex = gl_FragCoord.xy - vec2(0.5);
    vec2 ditherCoord = floor(pixelIndex * coordScale) + vec2(0.5);
    vec2 noiseUv = fract(ditherCoord / noiseSize);
    vec3 n = texture(noiseSampler, noiseUv).rgb; // [0,1)

    vec3 x = color * 255.0 + (n - 0.5) * strength;
    vec3 q = floor(x + 0.5); // round to nearest 8-bit value
    return clamp(q, 0.0, 255.0) / 255.0;
}
