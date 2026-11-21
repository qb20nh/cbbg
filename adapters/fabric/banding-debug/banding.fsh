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

#if VIEW == 2
// Follow a rasterized step that can move one pixel between neighboring rows.
bool followStep(inout ivec2 edge, ivec2 normal, ivec2 orientation, ivec3 nearColor,
                ivec3 farColor, float strength) {
    ivec3 first = quantized(edge, orientation, strength);
    ivec3 second = quantized(edge + normal, orientation, strength);
    if (first == farColor && second == farColor) {
        edge -= normal;
        return quantized(edge, orientation, strength) == nearColor &&
               quantized(edge - normal, orientation, strength) == nearColor;
    }
    if (first == nearColor && second == nearColor) {
        edge += normal;
        return quantized(edge + normal, orientation, strength) == farColor &&
               quantized(edge + 2 * normal, orientation, strength) == farColor;
    }
    return first == nearColor && second == farColor &&
           quantized(edge - normal, orientation, strength) == nearColor &&
           quantized(edge + 2 * normal, orientation, strength) == farColor;
}

bool smallStep(ivec2 normal, ivec2 tangent, ivec2 orientation, ivec3 nearColor, float strength) {
    ivec3 farColor = quantized(normal, orientation, strength);
    ivec3 change = abs(farColor - nearColor);
    int magnitude = max(change.r, max(change.g, change.b));
    if (magnitude < 1 || magnitude > 4 ||
        quantized(-normal, orientation, strength) != nearColor ||
        quantized(2 * normal, orientation, strength) != farColor) {
        return false;
    }
    for (int direction = -1; direction <= 1; direction += 2) {
        ivec2 edge = ivec2(0);
        for (int i = 0; i < 2; ++i) {
            edge += direction * tangent;
            if (!followStep(edge, normal, orientation, nearColor, farColor, strength)) {
                return false;
            }
        }
    }
    return true;
}
#endif

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
#if VIEW == 0
    outputColor = vec3(color & 1);
#elif VIEW == 1
    vec3 contrast = (outputColor - 0.5) * 2.0 + 0.5;
    float luma = dot(contrast, vec3(0.2126, 0.7152, 0.0722));
    outputColor = clamp(mix(vec3(luma), contrast, 2.0), 0.0, 1.0);
#elif VIEW == 2
    if (smallStep(ivec2(1, 0), ivec2(0, 1), orientation, color, strength) ||
        smallStep(ivec2(0, 1), ivec2(1, 0), orientation, color, strength)) {
        outputColor = vec3(1.0, 0.87, 0.15);
    } else {
        outputColor *= 0.32;
    }
#endif
    fragColor = vec4(outputColor, texture(InSampler, uv).a);
}
