uniform sampler2D InSampler;
uniform sampler2D NoiseSampler;
#ifdef LEGACY_UNIFORMS
uniform float Strength;
uniform vec2 CoordScale;
#else
layout(std140) uniform CbbgDitherInfo {
    float Strength;
#ifdef VULKAN_WINDOW
    float NoiseInvSize;
#endif
    vec2 CoordScale;
#ifdef VULKAN_WINDOW
    vec2 WindowSize;
#endif
};
#endif

#ifdef EXPLICIT_LOCATIONS
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
#else
in vec2 texCoord;
out vec4 fragColor;
#endif

vec2 sourceUv() {
#ifdef VULKAN_WINDOW
    return vec2(texCoord.x, 1.0 - texCoord.y);
#else
    return texCoord;
#endif
}

// Offsets are in dither output pixels, before any window scaling.
ivec3 quantized(ivec2 offset, ivec2 orientation, float strength) {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 origin = ivec2(sourceUv() * vec2(size));
    ivec2 source = clamp(origin + offset * orientation, ivec2(0), size - 1);
    vec2 pixel = gl_FragCoord.xy + vec2((source - origin) * orientation);
    vec3 color = clamp(texelFetch(InSampler, source, 0).rgb, 0.0, 1.0);
#ifdef VULKAN_WINDOW
    pixel.y = WindowSize.y - pixel.y;
    vec2 noiseUv = (floor(pixel * CoordScale) + 0.5) * NoiseInvSize;
#else
    vec2 noiseSize = vec2(textureSize(NoiseSampler, 0));
    vec2 noiseUv = fract((floor(pixel * CoordScale) + 0.5) / noiseSize);
#endif
    vec3 noise = textureLod(NoiseSampler, noiseUv, 0.0).rgb;
    return ivec3(clamp(floor(color * 255.0 + (noise - 0.5) * strength + 0.5), 0.0, 255.0));
}

bool smallStep(ivec2 normal, ivec2 tangent, ivec2 orientation, ivec3 low, float strength) {
    ivec3 high = quantized(normal, orientation, strength);
    ivec3 change = abs(high - low);
    int magnitude = max(change.r, max(change.g, change.b));
    if (magnitude < 1 || magnitude > 4 ||
        quantized(-normal, orientation, strength) != low ||
        quantized(2 * normal, orientation, strength) != high) {
        return false;
    }
    for (int i = -2; i <= 2; ++i) {
        if (quantized(tangent * i, orientation, strength) != low ||
            quantized(normal + tangent * i, orientation, strength) != high) {
            return false;
        }
    }
    return true;
}

void main() {
    // Evaluate all neighbors with the center pixel's strength. The split itself
    // is a display decision and must never become an input to edge detection.
#if SIDE == 0
    float strength = gl_FragCoord.x >= floor(float(textureSize(InSampler, 0).x) * 0.5)
        ? Strength : 0.0;
#elif SIDE == 1
    float strength = 0.0;
#else
    float strength = Strength;
#endif
    // Use the vertex shader's orientation for input texels while keeping STBN
    // aligned to the same framebuffer pixels as the ordinary dither pass.
    vec2 uv = sourceUv();
    ivec2 orientation = ivec2(sign(vec2(dFdx(uv.x), dFdy(uv.y))));
    ivec3 color = quantized(ivec2(0), orientation, strength);
    vec3 outputColor = vec3(color) / 255.0;
#if VIEW >= 1 && VIEW <= 4
    ivec3 bits = color & 1;
#if VIEW == 4
    outputColor = vec3(bits);
#else
    outputColor = vec3(float(bits[VIEW - 1]));
#endif
#elif VIEW == 5
    if (smallStep(ivec2(1, 0), ivec2(0, 1), orientation, color, strength) ||
        smallStep(ivec2(0, 1), ivec2(1, 0), orientation, color, strength)) {
        outputColor = vec3(1.0, 0.87, 0.15);
    } else {
        outputColor *= 0.32;
    }
#endif
    fragColor = vec4(outputColor, texture(InSampler, uv).a);
}
